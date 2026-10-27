package net.phoxis.tvmqtt;
import org.junit.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class SonyApiTest {
    private ServerSocket server;
    private Thread serverThread;
    private List<JSONObject> requests;
    private SonyApi sony;
    private volatile JSONObject textResponse;
    @Before public void start() throws Exception {
        requests=Collections.synchronizedList(new ArrayList<>());
        server=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
        serverThread=new Thread(() -> {
            while(!server.isClosed()) {
                try(Socket socket=server.accept()) {
                    InputStream input=socket.getInputStream();
                    ByteArrayOutputStream header=new ByteArrayOutputStream();
                    int ch;
                    while((ch=input.read())!=-1) {
                        header.write(ch);
                        if(header.toString("ISO-8859-1").endsWith("\r\n\r\n")) break;
                    }
                    String headers=header.toString("ISO-8859-1");
                    int length=0;
                    for(String line:headers.split("\r\n")) if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))
                        length=Integer.parseInt(line.split(":",2)[1].trim());
                    byte[] body=new byte[length];
                    int offset=0,n;
                    while(offset<length && (n=input.read(body,offset,length-offset))!=-1) offset+=n;
                    if (headers.startsWith("POST /sony/IRCC ")) {
                        String xml=new String(body,StandardCharsets.UTF_8);
                        requests.add(Jsons.object("method","IRCC","xml",xml,"headers",headers));
                        byte[] reply="OK".getBytes(StandardCharsets.US_ASCII);
                        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().write(reply);
                        continue;
                    }
                    JSONObject request=new JSONObject(new String(body,StandardCharsets.UTF_8));
                    requests.add(request);
                    assertTrue(headers.toLowerCase(Locale.ROOT).contains("x-auth-psk: private"));
                    JSONObject response=Jsons.object("result",new JSONArray());
                    if(request.getString("method").equals("getPowerStatus"))
                        response=Jsons.object("result",new JSONArray().put(Jsons.object("status","standby")));
                    if(request.getString("method").equals("getRemoteControllerInfo"))
                        response=Jsons.object("result",new JSONArray().put(new JSONObject()).put(
                            new JSONArray().put(Jsons.object("name","PowerOff","value","AAAA"))));
                    if(request.getString("method").equals("setTextForm") && textResponse!=null) response=textResponse;
                    byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);
                    OutputStream output=socket.getOutputStream();
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    output.write(bytes); output.flush();
                } catch(Exception e) { if(!server.isClosed()) throw new RuntimeException(e); }
            }
        });
        serverThread.setDaemon(true); serverThread.start();
        AppConfig config=new AppConfig(Jsons.object("mqtt",Jsons.object("uri","mqtt://localhost:1883","clientId","test","baseTopic","tvs/test"),
                "tv",Jsons.object("baseUrl","http://127.0.0.1:"+server.getLocalPort(),"preSharedKey","private")));
        sony=new SonyApi(config);
    }
    @After public void stop() throws Exception { server.close(); serverThread.join(1000); }
    @Test public void standbySnapshotAvoidsUnsupportedVolumeQueries() throws Exception {
        JSONObject state=sony.snapshot();
        assertEquals("off",state.getString("state")); assertTrue(state.isNull("volume")); assertEquals(1,requests.size());
    }
    @Test public void textUsesFirmwareStringParameterAndNoShell() throws Exception {
        new TvCommands(sony,() -> new JSONObject()).execute(Jsons.object("action","text","value","some text ' & ; $"));
        assertEquals("setTextForm",requests.get(0).getString("method"));
        assertEquals("some text ' & ; $",requests.get(0).getJSONArray("params").getString(0));
    }
    @Test public void remoteKeyUsesAdvertisedIrccCodeAndSoapAuthentication() throws Exception {
        new TvCommands(sony,() -> new JSONObject()).execute(Jsons.object("action","key","value","power_off"));
        assertEquals("getRemoteControllerInfo",requests.get(0).getString("method"));
        JSONObject sent=requests.get(1);
        assertEquals("IRCC",sent.getString("method"));
        assertTrue(sent.getString("xml").contains("<IRCCCode>AAAA</IRCCCode>"));
        assertTrue(sent.getString("headers").toLowerCase(Locale.ROOT).contains("x-auth-psk: private"));
        assertTrue(sent.getString("headers").contains("#X_SendIRCC"));
    }
    @Test public void invalidVolumeDoesNotReachTv() throws Exception {
        try {
            new TvCommands(sony,() -> new JSONObject()).execute(Jsons.object("action","volume_set","value",101));
            fail();
        } catch(CommandFailure e) { assertEquals("invalid_value",e.code); }
        assertEquals(0,requests.size());
    }
    @Test public void androidTextAvoidsSonyAndPreservesUnicode() throws Exception {
        final String value="caf\u00e9 \u65e5\u672c\u8a9e \ud83d\ude00";
        JSONObject result=new TvCommands(sony,() -> new JSONObject(),text -> {
            assertEquals(value,text);return Jsons.object("accepted",true,"method","android_focused_text");
        }).execute(Jsons.object("action","text","value",value));
        assertEquals("android_focused_text",result.getString("method"));assertEquals(0,requests.size());
    }
    @Test public void absentAndroidTextFallsBackToSony() throws Exception {
        JSONObject result=new TextEntry(sony,text -> { throw new CommandFailure("text_access_unavailable","not enabled"); }).send("test");
        assertEquals("sony_text_form",result.getString("method"));assertEquals(1,requests.size());
    }
    @Test public void sonyIllegalStateReportsFocusedFieldRequirement() throws Exception {
        textResponse=Jsons.object("error",new JSONArray().put(7).put("Illegal State"));
        try {
            new TextEntry(sony,text -> { throw new CommandFailure("text_not_focused","Focus the search text box"); }).send("test");
            fail();
        } catch(CommandFailure error) { assertEquals("text_not_focused",error.code); }
        assertEquals(1,requests.size());
    }
    @Test public void uncertainNativeTextIsNeverRetriedThroughSony() throws Exception {
        try {
            new TextEntry(sony,text -> { throw new CommandFailure("text_timeout","may have executed"); }).send("test");
            fail();
        } catch(CommandFailure error) { assertEquals("text_timeout",error.code); }
        assertEquals(0,requests.size());
    }
    @Test public void sonyTextAuthenticationErrorIsPreserved() throws Exception {
        textResponse=Jsons.object("error",new JSONArray().put(403).put("Forbidden"));
        try {
            new TextEntry(sony,text -> { throw new CommandFailure("text_not_supported","not supported"); }).send("test");
            fail();
        } catch(CommandFailure error) { assertEquals("sony_403",error.code); }
    }
    @Test public void invalidTextNeverReachesEitherWriter() throws Exception {
        try {
            new TvCommands(sony,() -> new JSONObject(),text -> { fail("Must not execute invalid input");return null; })
                .execute(Jsons.object("action","text","value",""));
            fail();
        } catch(CommandFailure error) { assertEquals("invalid_value",error.code); }
        assertEquals(0,requests.size());
    }
    @Test public void backspaceRoutesToAndroidWriterWithoutSonyTextForm() throws Exception {
        TextEntry.Writer writer=new TextEntry.Writer() {
            public JSONObject set(String value) { fail("Must not send text");return null; }
            public JSONObject backspace() throws Exception { return Jsons.object("accepted",true,"method","android_adb"); }
        };
        JSONObject ack=new TvCommands(sony,() -> new JSONObject(),writer).execute(Jsons.object("action","backspace"));
        assertEquals("android_adb",ack.getString("method"));assertEquals(0,requests.size());
    }
    @Test public void uncertainBackspaceDoesNotFallBackOrRepeat() throws Exception {
        TextEntry.Writer writer=new TextEntry.Writer() {
            public JSONObject set(String value) { fail("Must not send text");return null; }
            public JSONObject backspace() throws Exception { throw new CommandFailure("text_execution_uncertain","may have executed"); }
        };
        try {
            new TvCommands(sony,() -> new JSONObject(),writer).execute(Jsons.object("action","backspace"));fail();
        } catch(CommandFailure error) { assertEquals("text_execution_uncertain",error.code); }
        assertEquals(0,requests.size());
    }
    @Test public void selectAllRoutesToOneAndroidOperation() throws Exception {
        final int[] calls={0};
        TextEntry.Writer writer=new TextEntry.Writer() {
            public JSONObject set(String value) { fail("Must not send text");return null; }
            public JSONObject selectAll() throws Exception { calls[0]++;return Jsons.object("accepted",true); }
        };
        assertTrue(new TvCommands(sony,() -> new JSONObject(),writer).execute(Jsons.object("action","select_all")).getBoolean("accepted"));
        assertEquals(1,calls[0]);assertEquals(0,requests.size());
    }
    @Test public void countedBackspaceRoutesAsOneOperationAndRejectsInvalidCounts() throws Exception {
        final int[] counts={0,0};
        TextEntry.Writer writer=new TextEntry.Writer() {
            public JSONObject set(String value) { fail();return null; }
            public JSONObject backspace(int count) throws Exception { counts[0]++;counts[1]=count;return Jsons.object("accepted",true); }
        };
        TvCommands commands=new TvCommands(sony,() -> new JSONObject(),writer);
        commands.execute(Jsons.object("action","backspace","value",12));
        assertEquals(1,counts[0]);assertEquals(12,counts[1]);
        for(Object invalid:new Object[]{0,241,1.5,"12"}) {
            try { commands.execute(Jsons.object("action","backspace","value",invalid));fail(); }
            catch(CommandFailure expected) { assertEquals("invalid_value",expected.code); }
        }
        assertEquals(1,counts[0]);assertEquals(0,requests.size());
    }
}
