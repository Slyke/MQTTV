package net.phoxis.tvmqtt;

import org.junit.*;
import org.json.*;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class PhoneApiTest {
    private ApiServerGroup server;private int port;private AtomicInteger commands;
    private final String key="phone-api-fixture";
    @Before public void start() throws Exception {
        try(ServerSocket socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) { port=socket.getLocalPort(); }
        AppConfig config=new AppConfig(Jsons.object("mqtt",Jsons.object("uri","mqtt://broker","clientId","test"),
                "tv",Jsons.object("driver","nvidia-shield"),"api",Jsons.object("passkey",key,"bindAddress","127.0.0.1",
                        "http",Jsons.object("enabled",true,"port",port))));
        commands=new AtomicInteger();
        ControlBackend backend=new ControlBackend() {
            public CompletableFuture<JSONObject> currentState() {
                try { return CompletableFuture.completedFuture(Jsons.object("power","active")); } catch(Exception e) { throw new RuntimeException(e); }
            }
            public CompletableFuture<JSONObject> apiCommand(byte[] bytes) {
                commands.incrementAndGet();
                try { return CompletableFuture.completedFuture(Jsons.object("ok",true,"command_id",new JSONObject(new String(bytes,StandardCharsets.UTF_8)).getString("id"))); }
                catch(Exception e) { throw new RuntimeException(e); }
            }
            public void addStateListener(Consumer<JSONObject> listener) {}
            public void removeStateListener(Consumer<JSONObject> listener) {}
        };
        server=new ApiServerGroup(config,backend,()->null,name->("PHONE-ASSET:"+name).getBytes(StandardCharsets.UTF_8));server.start();
    }
    @After public void stop() { if(server!=null) server.stop(); }
    private String origin() { return "http://127.0.0.1:"+port; }
    private Response request(String method,String path,String headers,String body) throws Exception {
        byte[] data=body==null?new byte[0]:body.getBytes(StandardCharsets.UTF_8);
        try(Socket socket=new Socket("127.0.0.1",port)) {
            socket.setSoTimeout(3000);
            String message=method+" "+path+" HTTP/1.1\r\nHost: 127.0.0.1:"+port+"\r\nConnection: close\r\n"+
                    headers+(body==null?"":"Content-Type: application/json\r\nContent-Length: "+data.length+"\r\n")+"\r\n";
            socket.getOutputStream().write(message.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(data);
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] block=new byte[4096];int n;
            while((n=socket.getInputStream().read(block))!=-1) out.write(block,0,n);
            return new Response(new String(out.toByteArray(),StandardCharsets.UTF_8));
        }
    }
    private String login() throws Exception {
        Response result=request("POST","/api/session","X-API-Key: "+key+"\r\nOrigin: "+origin()+"\r\n",null);
        assertEquals(200,result.status);String cookie=result.headers.get("set-cookie");
        assertTrue(cookie.contains("HttpOnly"));return cookie.split(";",2)[0];
    }
    @Test public void staticRemoteLoadsWithoutExposingApiOrAcceptingQueryCredentials() throws Exception {
        Response root=request("GET","/","",null);assertEquals(200,root.status);
        assertTrue(root.body.contains("PHONE-ASSET:index.html"));assertFalse(root.body.contains(key));
        assertTrue(root.headers.get("content-security-policy").contains("frame-ancestors 'none'"));
        assertEquals(200,request("GET","/app.js","",null).status);
        assertEquals(401,request("GET","/api/state?key="+key,"",null).status);
    }
    @Test public void scannedKeyCreatesBrowserSessionForStateAndCommands() throws Exception {
        String cookie=login();
        Response state=request("GET","/api/state","Cookie: "+cookie+"\r\n",null);
        assertEquals(200,state.status);assertEquals("active",new JSONObject(state.body).getJSONObject("state").getString("power"));
        Response ack=request("POST","/api/command","Cookie: "+cookie+"\r\nOrigin: "+origin()+"\r\n",
                "{\"id\":\"phone\",\"action\":\"left\"}");
        assertEquals(200,ack.status);assertEquals(1,commands.get());
    }
    @Test public void sessionLoginAndMutatingCookieRequestsRejectForeignOrigins() throws Exception {
        assertEquals(401,request("POST","/api/session","",null).status);
        assertEquals(403,request("POST","/api/session","X-API-Key: "+key+"\r\nOrigin: https://evil.invalid\r\n",null).status);
        String cookie=login();
        String command="{\"id\":\"blocked\",\"action\":\"left\"}";
        assertEquals(403,request("POST","/api/command","Cookie: "+cookie+"\r\nOrigin: https://evil.invalid\r\n",command).status);
        assertEquals(403,request("POST","/api/command","Cookie: "+cookie+"\r\n",command).status);
        assertEquals(0,commands.get());
    }
    @Test public void websocketBrowserSessionRequiresMatchingOrigin() throws Exception {
        String cookie=login();
        for(boolean allowed:new boolean[]{false,true}) {
            try(Socket socket=new Socket("127.0.0.1",port)) {
                socket.setSoTimeout(3000);
                String message="GET /api/ws HTTP/1.1\r\nHost: 127.0.0.1:"+port+"\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n"+
                        "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nCookie: "+cookie+"\r\n"+
                        "Origin: "+(allowed?origin():"https://evil.invalid")+"\r\n\r\n";
                socket.getOutputStream().write(message.getBytes(StandardCharsets.US_ASCII));
                String status=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII)).readLine();
                assertTrue(status,status.startsWith("HTTP/1.1 "+(allowed?"101":"403")));
            }
        }
    }
    private static final class Response {
        final int status;final Map<String,String> headers=new HashMap<>();final String body;
        Response(String raw) {
            String[] chunks=raw.split("\r\n\r\n",2);String[] lines=chunks[0].split("\r\n");
            status=Integer.parseInt(lines[0].split(" ")[1]);
            for(int i=1;i<lines.length;i++) { String[] item=lines[i].split(":",2);if(item.length==2) headers.put(item[0].toLowerCase(Locale.ROOT),item[1].trim()); }
            body=chunks.length>1?chunks[1]:"";
        }
    }
}
