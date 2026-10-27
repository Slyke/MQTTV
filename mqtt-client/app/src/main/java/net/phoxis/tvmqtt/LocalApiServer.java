package net.phoxis.tvmqtt;

import fi.iki.elonen.*;
import fi.iki.elonen.NanoWSD.WebSocketFrame.*;
import org.json.*;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LocalApiServer extends NanoWSD {
    private final AppConfig config;
    private final ControlBackend backend;
    private final ApiServerGroup group;
    private final ApiResources resources;
    private final ThreadLocal<Socket> servingSocket=new ThreadLocal<>();

    LocalApiServer(AppConfig config,ControlBackend backend,ApiServerGroup group,ApiResources resources,int port) {
        super(config.apiBindAddress,port);
        this.config=config;this.backend=backend;this.group=group;this.resources=resources;
        setAsyncRunner(resources);
    }
    @Override protected ClientHandler createClientHandler(Socket socket,InputStream input) {
        return new ClientHandler(input,socket) {
            public void run() {
                servingSocket.set(socket);
                try { super.run(); } finally { servingSocket.remove(); }
            }
        };
    }
    @Override public Response serve(IHTTPSession session) {
        String path=session.getUri();
        if(path.equals("/") || path.equals("/app.js") || path.equals("/style.css")) return bootstrap(session);
        Map<String,String> headers=session.getHeaders();
        boolean secure=servingSocket.get() instanceof javax.net.ssl.SSLSocket;
        boolean header=ApiAuth.matches(config.apiPasskey,headers.get("x-api-key"));
        boolean browser=!headers.containsKey("x-api-key") && group.browserSessions.authorized(headers.get("cookie"),System.currentTimeMillis()/1000,secure);
        boolean origin=BrowserSessions.sameOrigin(headers.get("origin"),headers.get("host"),secure);
        if(!header && !browser || path.equals("/api/session") && !header) {
            discardSmallBody(session);
            Response reply=error(401,"unauthorized","Supply X-API-Key or scan Open on phone");
            reply.addHeader("WWW-Authenticate","ApiKey realm=\"TV\"");
            return reply;
        }
        if(browser && ((session.getMethod()!=Method.GET || isWebsocketRequested(session)) && !origin ||
                headers.containsKey("origin") && !origin) ||
                path.equals("/api/session") && headers.containsKey("origin") && !origin) {
            discardSmallBody(session);return error(403,"origin_rejected","Use the TV remote page from this address");
        }
        if(path.equals("/api/session")) {
            if(session.getMethod()!=Method.POST) return error(405,"method_not_allowed","Use POST");
            try {
                Response response=json(200,Jsons.object("ok",true));
                response.addHeader("Set-Cookie",group.browserSessions.cookie(secure,System.currentTimeMillis()/1000));
                return response;
            } catch(Exception e) { return error(503,"unavailable","Could not create browser session"); }
        }
        if(isWebsocketRequested(session)) {
            if(!"/api/ws".equals(session.getUri())) return error(404,"not_found","Unknown endpoint");
            if(session.getMethod()!=Method.GET) return error(405,"method_not_allowed","Use GET for WebSocket upgrade");
            try {
                String key=session.getHeaders().get("sec-websocket-key");
                if(!"13".equals(session.getHeaders().get("sec-websocket-version")) ||
                        key==null || key.length()>32 || Base64.getDecoder().decode(key).length!=16)
                    return error(400,"invalid_websocket","Invalid WebSocket handshake");
                if(!resources.websocketSlots.tryAcquire()) return error(503,"busy","WebSocket connection limit reached");
                try { servingSocket.get().setSoTimeout(45000); return super.serve(session); }
                catch(Exception e) { resources.websocketSlots.release(); return error(400,"invalid_websocket","WebSocket upgrade failed"); }
            } catch(IllegalArgumentException e) { return error(400,"invalid_websocket","Invalid WebSocket handshake"); }
        }
        return serveHttp(session);
    }
    private Response bootstrap(IHTTPSession session) {
        if(session.getMethod()!=Method.GET) return error(405,"method_not_allowed","Use GET");
        String name=session.getUri().equals("/")?"index.html":session.getUri().substring(1);
        try {
            byte[] bytes=group.assets.read(name);
            if(bytes==null) return error(404,"not_found","Phone remote is unavailable");
            String type=name.endsWith(".html")?"text/html; charset=UTF-8":name.endsWith(".js")?"text/javascript; charset=UTF-8":"text/css; charset=UTF-8";
            Response response=headers(newFixedLengthResponse(new ApiStatus(200),type,new ByteArrayInputStream(bytes),bytes.length));
            response.addHeader("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self' ws: wss:; base-uri 'none'; frame-ancestors 'none'");
            return response;
        } catch(IOException e) { return error(503,"unavailable","Phone remote is unavailable"); }
    }
    private void discardSmallBody(IHTTPSession session) {
        // Drain only small, already-sent request bodies so TLS close does not truncate a 401.
        // No JSON is parsed and no command is submitted before authentication.
        if(session.getHeaders().containsKey("transfer-encoding") || session.getHeaders().containsKey("expect")) return;
        Socket socket=servingSocket.get();
        int original=10000;
        try {
            long count=Long.parseLong(session.getHeaders().getOrDefault("content-length","0"));
            if(count<=0 || count>16384 || socket==null) return;
            original=socket.getSoTimeout();socket.setSoTimeout(250);
            byte[] buffer=new byte[1024];
            InputStream input=session.getInputStream();
            while(count>0) {
                int n=input.read(buffer,0,(int)Math.min(count,buffer.length));
                if(n<0) break;
                count-=n;
            }
        } catch(Exception ignored) {}
        finally { if(socket!=null) try { socket.setSoTimeout(original); } catch(IOException ignored) {} }
    }
    @Override protected Response serveHttp(IHTTPSession session) {
        String path=session.getUri();
        CompletableFuture<JSONObject> pending=null;
        try {
            if("/api/state".equals(path)) {
                if(session.getMethod()!=Method.GET) return error(405,"method_not_allowed","Use GET");
                pending=backend.currentState();
                return json(200,Jsons.object("state",await(pending)));
            }
            if("/api/command".equals(path)) {
                if(session.getMethod()!=Method.POST) return error(405,"method_not_allowed","Use POST");
                if(session.getHeaders().containsKey("transfer-encoding")) return error(400,"invalid_body","Use Content-Length, not chunked encoding");
                String length=session.getHeaders().get("content-length");
                if(length==null) return error(411,"length_required","Content-Length is required");
                long count;
                try { count=Long.parseLong(length); } catch(NumberFormatException e) { return error(400,"invalid_length","Invalid Content-Length"); }
                if(count<0) return error(400,"invalid_length","Invalid Content-Length");
                if(count>16384) return error(413,"payload_too_large","Commands are limited to 16 KiB");
                String type=session.getHeaders().get("content-type");
                if(type==null || !type.toLowerCase(Locale.ROOT).split(";",2)[0].trim().equals("application/json"))
                    return error(415,"unsupported_media_type","Use application/json");
                byte[] payload=new byte[(int)count];
                InputStream input=session.getInputStream();
                int offset=0,n;
                while(offset<payload.length && (n=input.read(payload,offset,payload.length-offset))!=-1) offset+=n;
                if(offset!=payload.length) return error(400,"incomplete_body","Request body is incomplete");
                pending=backend.apiCommand(payload);
                return json(200,await(pending));
            }
            if("/api/public-key".equals(path) || "/api/certificate".equals(path)) {
                if(session.getMethod()!=Method.GET) return error(405,"method_not_allowed","Use GET");
                HttpsIdentity identity=group.identity();
                if(path.equals("/api/public-key")) return json(200,identity.publicInfo());
                Response response=newFixedLengthResponse(new ApiStatus(200),"application/x-pem-file",identity.certificatePem());
                return headers(response);
            }
            if("/api/ws".equals(path)) return error(400,"websocket_required","Use a WebSocket upgrade");
            return error(404,"not_found","Unknown endpoint");
        } catch(TimeoutException e) {
            if(pending!=null) pending.cancel(false);
            return error(503,"request_timeout","Request timed out; retry the same command ID");
        } catch(Exception e) {
            if(pending!=null) pending.cancel(false);
            return error(503,"unavailable","Service is busy or unavailable");
        }
    }
    private JSONObject await(CompletableFuture<JSONObject> pending) throws Exception {
        return pending.get(config.apiRequestTimeoutMs,TimeUnit.MILLISECONDS);
    }
    private static Response headers(Response response) {
        response.addHeader("Cache-Control","no-store");
        response.addHeader("X-Content-Type-Options","nosniff");
        response.addHeader("Referrer-Policy","no-referrer");
        response.addHeader("X-Frame-Options","DENY");
        response.closeConnection(true);
        return response;
    }
    private static Response json(int code,JSONObject body) {
        return headers(newFixedLengthResponse(new ApiStatus(code),"application/json; charset=UTF-8",body.toString()));
    }
    private static Response error(int code,String name,String message) {
        try { return json(code,Jsons.object("error",Jsons.object("code",name,"message",message))); }
        catch(Exception impossible) { return headers(newFixedLengthResponse(new ApiStatus(500),"application/json","{}")); }
    }
    @Override protected WebSocket openWebSocket(IHTTPSession handshake) {
        Peer peer=new Peer(handshake,servingSocket.get());
        resources.peers.add(peer);
        return peer;
    }
    private final class Peer extends WebSocket implements ApiResources.Peer {
        private final Socket socket;
        private final ArrayBlockingQueue<WebSocketFrame> outbound=new ArrayBlockingQueue<>(16);
        private final AtomicBoolean closed=new AtomicBoolean();
        private Thread writer;
        Peer(IHTTPSession request,Socket socket) { super(request);this.socket=socket; }
        protected void onOpen() {
            writer=new Thread(() -> {
                try {
                    while(!closed.get()) {
                        WebSocketFrame frame=outbound.take();
                        sendFrame(frame);
                        if(frame.getOpCode()==OpCode.Close) break;
                    }
                } catch(Exception ignored) {} finally { abort(); }
            },"tv-api-websocket-writer");
            writer.setDaemon(true);writer.start();
            backend.currentState().whenComplete((state,error) -> {
                if(error==null) {
                    try { state(Jsons.object("state",state).toString()); } catch(Exception ignored) { abort(); }
                } else transportError("unavailable","State is unavailable",null);
            });
        }
        public void state(String payload) {
            try { offer(new WebSocketFrame(OpCode.Text,true,payload)); } catch(IOException e) { abort(); }
        }
        public void ping() { offer(new WebSocketFrame(OpCode.Ping,true,new byte[0])); }
        private void offer(WebSocketFrame frame) {
            if(!closed.get() && !outbound.offer(frame)) abort();
        }
        protected void onMessage(WebSocketFrame message) {
            if(message.getOpCode()!=OpCode.Text) {
                try { offer(new WebSocketFrame.CloseFrame(CloseCode.UnsupportedData,"JSON text frames required")); }
                catch(IOException e) { abort(); }
                return;
            }
            byte[] payload=message.getBinaryPayload();
            try { WebSocketFrame.binary2Text(payload); } catch(IOException e) { abort();return; }
            CompletableFuture<JSONObject> future=backend.apiCommand(payload);
            ScheduledFuture<?> timeout=resources.timers.schedule(() -> future.cancel(false),
                    config.apiRequestTimeoutMs,TimeUnit.MILLISECONDS);
            future.whenComplete((ack,error) -> {
                timeout.cancel(false);
                try {
                    if(error==null) state(Jsons.object("ack",ack).toString());
                    else transportError("unavailable","Request is unavailable or timed out; retry the same ID",commandId(payload));
                } catch(Exception ignored) { abort(); }
            });
        }
        private String commandId(byte[] payload) {
            try {
                Object id=new JSONObject(new String(payload,StandardCharsets.UTF_8)).opt("id");
                return id instanceof String && ((String)id).length()<=128?(String)id:null;
            } catch(Exception e) { return null; }
        }
        private void transportError(String code,String message,String id) {
            try { state(Jsons.object("error",Jsons.object("code",code,"message",message),"command_id",id).toString()); }
            catch(Exception ignored) { abort(); }
        }
        protected void onPong(WebSocketFrame frame) {}
        protected void onClose(CloseCode code,String reason,boolean remote) { abort(); }
        protected void onException(IOException error) { abort(); }
        public void abort() {
            if(!closed.compareAndSet(false,true)) return;
            resources.peers.remove(this);resources.websocketSlots.release();outbound.clear();
            try { socket.close(); } catch(IOException ignored) {}
            if(writer!=null) writer.interrupt();
        }
    }
    private static final class ApiStatus implements Response.IStatus {
        private final int code;
        ApiStatus(int code) { this.code=code; }
        public int getRequestStatus() { return code; }
        public String getDescription() {
            String text;
            switch(code) {
                case 200:text="OK";break;case 400:text="Bad Request";break;case 401:text="Unauthorized";break;
                case 403:text="Forbidden";break;case 404:text="Not Found";break;case 405:text="Method Not Allowed";break;case 411:text="Length Required";break;
                case 413:text="Payload Too Large";break;case 415:text="Unsupported Media Type";break;
                case 503:text="Service Unavailable";break;default:text="Internal Server Error";
            }
            return code+" "+text;
        }
    }
}
