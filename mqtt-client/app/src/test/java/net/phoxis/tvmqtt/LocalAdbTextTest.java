package net.phoxis.tvmqtt;

import org.junit.*;
import org.json.JSONObject;
import javax.crypto.Cipher;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class LocalAdbTextTest {
    private File keyFile;
    private KeyPair key;
    @Before public void key() throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);key=generator.generateKeyPair();
        keyFile=File.createTempFile("tv-text-test-",".pem");
        Files.write(keyFile.toPath(),("-----BEGIN PRIVATE KEY-----\n"+Base64.getEncoder().encodeToString(key.getPrivate().getEncoded())+
                "\n-----END PRIVATE KEY-----\n").getBytes(StandardCharsets.US_ASCII));
    }
    @After public void cleanup() { keyFile.delete(); }
    private void exercise(String mode,String expected) throws Exception {
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            FutureTask<Void> daemon=new FutureTask<>(() -> {
                try(Socket socket=server.accept()) {
                    socket.setSoTimeout(3000);
                    InputStream in=socket.getInputStream();OutputStream out=socket.getOutputStream();
                    assertEquals(LocalAdbText.CNXN,LocalAdbText.read(in).code);
                    byte[] challenge=new byte[20];Arrays.fill(challenge,(byte)0x36);
                    LocalAdbText.write(out,LocalAdbText.AUTH,1,0,challenge);
                    LocalAdbText.Packet auth=LocalAdbText.read(in);
                    assertEquals(LocalAdbText.AUTH,auth.code);assertEquals(2,auth.arg0);
                    Cipher verify=Cipher.getInstance("RSA/ECB/PKCS1Padding");verify.init(Cipher.DECRYPT_MODE,key.getPublic());
                    byte[] signed=verify.doFinal(auth.data);
                    assertArrayEquals(challenge,Arrays.copyOfRange(signed,signed.length-20,signed.length));
                    assertEquals(35,signed.length);
                    if(mode.equals("auth_reject")) {
                        LocalAdbText.write(out,LocalAdbText.AUTH,1,0,challenge);
                        assertEquals(-1,in.read()); // Client must not send its public key / prompt registration.
                        return null;
                    }
                    LocalAdbText.write(out,LocalAdbText.CNXN,0x01000000,4096,"device::\0".getBytes(StandardCharsets.US_ASCII));
                    LocalAdbText.Packet open=LocalAdbText.read(in);
                    assertEquals(LocalAdbText.OPEN,open.code);
                    String shell=new String(open.data,StandardCharsets.UTF_8);
                    assertTrue(shell.startsWith(mode.equals("backspace")?"shell:/system/bin/input keyevent 67;":"shell:/system/bin/input text 'a b'"));
                    assertTrue(shell.endsWith("\0"));
                    LocalAdbText.write(out,LocalAdbText.OKAY,7,1,new byte[0]);
                    if(mode.equals("disconnect")) return null;
                    byte[] reply=("\nTVMQTT_RC:"+(mode.equals("exit_failure")?"1":"0")+"\n").getBytes(StandardCharsets.UTF_8);
                    LocalAdbText.write(out,LocalAdbText.WRTE,7,1,reply);
                    assertEquals(LocalAdbText.OKAY,LocalAdbText.read(in).code);
                    LocalAdbText.write(out,LocalAdbText.CLSE,7,1,new byte[0]);
                    assertEquals(LocalAdbText.CLSE,LocalAdbText.read(in).code);
                }
                return null;
            });
            Thread thread=new Thread(daemon);thread.setDaemon(true);thread.start();
            try {
                LocalAdbText client=new LocalAdbText(server.getLocalPort(),keyFile);
                JSONObject result=mode.equals("backspace")?client.backspace():client.send("a b");
                assertNull(expected);assertEquals("android_adb",result.getString("method"));
            } catch(CommandFailure error) { assertEquals(expected,error.code); }
            daemon.get(5,TimeUnit.SECONDS);
        }
    }
    @Test public void backspaceUsesAuthenticatedFixedDeleteKey() throws Exception { exercise("backspace",null); }
    @Test public void loopbackAuthenticationAndExitStatus() throws Exception { exercise("success",null); }
    @Test public void unauthorizedKeyDoesNotTriggerTvPrompt() throws Exception { exercise("auth_reject","adb_authentication"); }
    @Test public void disconnectAfterOpenIsUncertainAndNotRetried() throws Exception { exercise("disconnect","text_execution_uncertain"); }
    @Test public void failedInputCommandIsNotAcknowledgedAsSuccess() throws Exception { exercise("exit_failure","text_injection_failed"); }
    @Test public void shellMetacharactersAreQuotedAsData() throws Exception {
        String payload="a'; echo unsafe; $(echo unsafe) '";
        String result=LocalAdbText.command(payload);
        assertTrue(result.startsWith("/system/bin/input text "+LocalAdbText.quote(payload)+"; result="));
        assertEquals("'a'\\''b'",LocalAdbText.quote("a'b"));
    }
    @Test public void percentSStaysLiteralAndSpacesStayWithinOneArgument() throws Exception {
        String result=LocalAdbText.command("a %s b");
        assertTrue(result.startsWith("/system/bin/input text 'a %' && /system/bin/input text 's b';"));
    }
    @Test public void customKeyboardRejectsUnsupportedUnicodeBeforeOpeningAdb() throws Exception {
        try { new LocalAdbText(1,keyFile).send("\u65e5");fail(); }
        catch(CommandFailure error) { assertEquals("unsupported_text_characters",error.code); }
    }
    @Test public void oversizedOrCorruptPacketsAreRejected() throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        LocalAdbText.write(bytes,LocalAdbText.AUTH,1,0,new byte[]{1,2,3});
        byte[] raw=bytes.toByteArray();raw[raw.length-1]^=1;
        try { LocalAdbText.read(new ByteArrayInputStream(raw));fail(); }
        catch(IOException expected) {}
    }
    @Test public void backspaceBatchUsesOneInputProcess() throws Exception {
        String value=LocalAdbText.backspaceCommand(20);
        assertTrue(value.startsWith("/system/bin/input keyevent"));
        assertEquals(20,value.split(" 67",-1).length-1);
        assertEquals(1,value.split("/system/bin/input",-1).length-1);
        for(int count:new int[]{0,241}) {
            try { LocalAdbText.backspaceCommand(count);fail(); }
            catch(CommandFailure expected) { assertEquals("invalid_value",expected.code); }
        }
    }
}
