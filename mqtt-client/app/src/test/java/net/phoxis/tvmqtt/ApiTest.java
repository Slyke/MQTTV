package net.phoxis.tvmqtt;
import org.junit.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.net.ssl.*;
import static org.junit.Assert.*;

public class ApiTest {
    private ApiServerGroup servers;
    private int port;
    private AtomicInteger commands;
    private String key="api-test-private";
    private AppConfig config(JSONObject api) throws Exception {
        return new AppConfig(Jsons.object("mqtt",Jsons.object("uri","mqtt://127.0.0.1:1883","clientId","api-test","baseTopic","tvs/test"),
                "tv",Jsons.object("baseUrl","http://127.0.0.1","preSharedKey","tv-key"),"api",api));
    }
    @Before public void setup() throws Exception {
        try(ServerSocket unused=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) { port=unused.getLocalPort(); }
        AppConfig options=config(Jsons.object("passkey",key,"bindAddress","127.0.0.1","http",Jsons.object("enabled",true,"port",port)));
        commands=new AtomicInteger();
        ControlBackend backend=new ControlBackend() {
            public CompletableFuture<JSONObject> apiCommand(byte[] payload) {
                commands.incrementAndGet();
                try { return CompletableFuture.completedFuture(Jsons.object("ok",true,"command_id",new JSONObject(new String(payload,StandardCharsets.UTF_8)).getString("id"))); }
                catch(Exception e) { CompletableFuture<JSONObject> failed=new CompletableFuture<>();failed.completeExceptionally(e);return failed; }
            }
            public CompletableFuture<JSONObject> currentState() {
                try { return CompletableFuture.completedFuture(Jsons.object("power","standby")); }
                catch(Exception e) { throw new RuntimeException(e); }
            }
            public void addStateListener(Consumer<JSONObject> listener) {}
            public void removeStateListener(Consumer<JSONObject> listener) {}
        };
        HttpsIdentity identity=new HttpsIdentity() {
            public SSLServerSocketFactory socketFactory() { return (SSLServerSocketFactory)SSLServerSocketFactory.getDefault(); }
            public JSONObject publicInfo() throws Exception { return Jsons.object("public_key_pem","PUBLIC_ONLY","persistent",true); }
            public String certificatePem() { return "PUBLIC_CERTIFICATE"; }
        };
        servers=new ApiServerGroup(options,backend,() -> identity);
        servers.start();
    }
    @After public void stop() { if(servers!=null) servers.stop(); }
    private JSONObject request(String method,String path,String password,String body) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+port+path).openConnection();
        connection.setConnectTimeout(3000);connection.setReadTimeout(3000);connection.setRequestMethod(method);
        if(password!=null) connection.setRequestProperty(ApiAuth.HEADER,password);
        if(body!=null) {
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);connection.setFixedLengthStreamingMode(bytes.length);connection.setRequestProperty("Content-Type","application/json");
            try(OutputStream out=connection.getOutputStream()) { out.write(bytes); }
        }
        try {
            int code=connection.getResponseCode();
            InputStream input=code<400?connection.getInputStream():connection.getErrorStream();
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            if(input!=null) {
                byte[] block=new byte[1024];int n;
                while((n=input.read(block))!=-1) bytes.write(block,0,n);
                input.close();
            }
            return Jsons.object("http",code,"body",bytes.size()==0?new JSONObject():new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8)));
        } finally { connection.disconnect(); }
    }
    @Test public void missingWrongAndQueryStringCredentialsAreRejected() throws Exception {
        assertEquals(401,request("GET","/api/state",null,null).getInt("http"));
        assertEquals(401,request("GET","/api/state","wrong",null).getInt("http"));
        assertEquals(401,request("GET","/api/state?passkey="+key,null,null).getInt("http"));
        assertEquals(401,request("POST","/api/command",null,"{\"id\":\"no-auth\",\"action\":\"left\"}").getInt("http"));
        assertEquals(0,commands.get());
    }
    @Test public void currentStateUsesStateEnvelopeAndCommandUsesSamePayload() throws Exception {
        JSONObject state=request("GET","/api/state",key,null);
        assertEquals(200,state.getInt("http"));
        assertEquals("standby",state.getJSONObject("body").getJSONObject("state").getString("power"));
        JSONObject ack=request("POST","/api/command",key,"{\"id\":\"phone\",\"action\":\"left\"}");
        assertEquals(200,ack.getInt("http"));assertEquals("phone",ack.getJSONObject("body").getString("command_id"));
        assertEquals(1,commands.get());
    }
    @Test public void wrongMethodsUnknownPathsAndOversizeAreRejectedBeforeCommandHandling() throws Exception {
        assertEquals(405,request("POST","/api/state",key,"{}").getInt("http"));
        assertEquals(404,request("GET","/private-key",key,null).getInt("http"));
        char[] large=new char[17000];Arrays.fill(large,'x');
        assertEquals(413,request("POST","/api/command",key,new String(large)).getInt("http"));
        assertEquals(0,commands.get());
    }
    @Test public void publicKeyAlsoRequiresHeaderAndContainsOnlyPublicMaterial() throws Exception {
        assertEquals(401,request("GET","/api/public-key",null,null).getInt("http"));
        JSONObject reply=request("GET","/api/public-key",key,null);
        assertEquals(200,reply.getInt("http"));
        assertEquals("PUBLIC_ONLY",reply.getJSONObject("body").getString("public_key_pem"));
        assertFalse(reply.toString().contains(key));
    }
    @Test public void websocketAuthorizationIsCheckedBeforeUpgrade() throws Exception {
        try(Socket socket=new Socket("127.0.0.1",port)) {
            socket.setSoTimeout(3000);
            String handshake="GET /api/ws HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n";
            socket.getOutputStream().write(handshake.getBytes(StandardCharsets.US_ASCII));
            BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
            assertTrue(input.readLine().startsWith("HTTP/1.1 401"));
        }
    }
    @Test public void listenersDefaultOffAndEnabledListenersNeedAHeaderSafeKey() throws Exception {
        AppConfig disabled=config(new JSONObject());
        assertFalse(disabled.apiHttpEnabled);assertFalse(disabled.apiHttpsEnabled);
        try { config(Jsons.object("http",Jsons.object("enabled",true)));fail(); } catch(IllegalArgumentException expected) {}
        try { config(Jsons.object("passkey","bad\r\nkey","http",Jsons.object("enabled",true)));fail(); } catch(IllegalArgumentException expected) {}
        try { config(Jsons.object("passkey","key","http",Jsons.object("enabled",true,"port",9000),
                "https",Jsons.object("enabled",true,"port",9000)));fail(); } catch(IllegalArgumentException expected) {}
    }
    @Test public void authChecksEntireCaseSensitivePasskey() {
        assertTrue(ApiAuth.matches(key,key));
        assertFalse(ApiAuth.matches(key,key+"x"));assertFalse(ApiAuth.matches(key,"API-TEST-PRIVATE"));
        assertFalse(ApiAuth.matches("",key));assertFalse(ApiAuth.matches(key,null));
    }
}
