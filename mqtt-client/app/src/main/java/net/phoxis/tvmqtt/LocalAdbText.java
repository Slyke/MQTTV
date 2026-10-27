package net.phoxis.tvmqtt;

import org.json.JSONObject;
import javax.crypto.Cipher;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;

public final class LocalAdbText {
    static final int CNXN=0x4e584e43,AUTH=0x48545541,OPEN=0x4e45504f,OKAY=0x59414b4f,WRTE=0x45545257,CLSE=0x45534c43;
    private final int port;
    private final File keyFile;
    public LocalAdbText(int port,File keyFile) { this.port=port;this.keyFile=keyFile; }
    public JSONObject send(String text) throws Exception {
        return execute(command(text));
    }
    public JSONObject key(int code) throws Exception {
        if(code<0 || code>300) throw new CommandFailure("invalid_value","Invalid Android key code");
        return execute(finish("/system/bin/input keyevent "+code));
    }
    public JSONObject launch(String component) throws Exception {
        if(!component.matches("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+"))
            throw new CommandFailure("invalid_value","Invalid Android application component");
        return execute(finish("/system/bin/am start -n "+quote(component)));
    }
    public JSONObject backspace() throws Exception { return backspace(1); }
    public JSONObject backspace(int count) throws Exception { return execute(backspaceCommand(count)); }
    static String backspaceCommand(int count) throws CommandFailure {
        if(count<1 || count>240) throw new CommandFailure("invalid_value","Backspace count must be 1-240.");
        StringBuilder keys=new StringBuilder("/system/bin/input keyevent");
        for(int i=0;i<count;i++) keys.append(" 67");
        return finish(keys.toString());
    }
    public JSONObject selectAll(String apkPath) throws Exception {
        if(apkPath==null || !apkPath.startsWith("/data/app/") || !apkPath.endsWith("/base.apk") || apkPath.indexOf(0)>=0)
            throw new CommandFailure("text_edit_unavailable","Cannot locate the installed keyboard shortcut.");
        return execute(finish("CLASSPATH="+quote(apkPath)+" /system/bin/app_process /system/bin net.phoxis.tvmqtt.ShellSelectAll"));
    }
    private JSONObject execute(String shell) throws Exception {
        executeOutput(shell);
        return Jsons.object("accepted",true,"method","android_adb","host","127.0.0.1");
    }
    private String executeOutput(String shell) throws Exception {
        String result=new String(stream("shell:"+shell,16384),StandardCharsets.UTF_8);
        int marker=result.lastIndexOf("\nTVMQTT_RC:");
        if(marker<0 || !result.substring(marker).trim().equals("TVMQTT_RC:0"))
            throw new CommandFailure("text_injection_failed","Android did not complete the text operation.");
        return result;
    }
    private byte[] stream(String service,int maximumOutput) throws Exception {
        PrivateKey key=loadKey(keyFile);
        boolean opened=false;
        long deadline=System.nanoTime()+12000000000L;
        try(Socket socket=new Socket()) {
            // Only the TV's own already-enabled adbd is reachable through this client.
            socket.connect(new InetSocketAddress("127.0.0.1",port),2000);
            socket.setSoTimeout(5000);
            InputStream in=socket.getInputStream();OutputStream out=socket.getOutputStream();
            write(out,CNXN,0x01000000,4096,"host::\0".getBytes(StandardCharsets.US_ASCII));
            Packet hello=read(in);
            if(hello.code==AUTH && hello.arg0==1 && hello.data.length==20) {
                write(out,AUTH,2,0,sign(key,hello.data));
                hello=read(in);
            }
            if(hello.code==AUTH) throw new CommandFailure("adb_authentication",
                    "The TV did not accept the configured ADB key. Reuse a key already authorized on this TV.");
            // Never send a public-key registration packet: no on-screen authorization prompt.
            if(hello.code!=CNXN) throw new IOException("Invalid ADB connection reply");
            opened=true;
            write(out,OPEN,1,0,(service+"\0").getBytes(StandardCharsets.UTF_8));
            Packet ready=read(in);
            if(ready.code!=OKAY || ready.arg1!=1) throw new IOException("ADB shell was not opened");
            int remote=ready.arg0;
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            for(int count=0;count<128;count++) {
                if(System.nanoTime()>deadline) throw new SocketTimeoutException();
                Packet packet=read(in);
                if(packet.arg0!=remote || packet.arg1!=1) throw new IOException("Invalid ADB stream");
                if(packet.code==WRTE) {
                    if(output.size()+packet.data.length>maximumOutput) throw new IOException("ADB output exceeded limit");
                    output.write(packet.data);write(out,OKAY,1,remote,new byte[0]);
                } else if(packet.code==CLSE) {
                    try { write(out,CLSE,1,remote,new byte[0]); } catch(IOException ignored) {}
                    return output.toByteArray();
                } else throw new IOException("Unexpected ADB stream reply");
            }
            throw new IOException("Too many ADB packets");
        } catch(CommandFailure error) { throw error; }
        catch(IOException error) {
            throw new CommandFailure(opened?"text_execution_uncertain":"adb_unavailable",
                    opened?"Text may have been entered; retry the same command ID rather than sending it again.":
                    "TV-local ADB is unavailable. Network debugging must remain enabled for this text fallback.");
        }
    }
    static String command(String text) throws CommandFailure {
        if(text==null || text.isEmpty() || text.length()>240) throw new CommandFailure("invalid_value","Text needs 1-240 characters.");
        for(int i=0;i<text.length();i++) if(text.charAt(i)<32 || text.charAt(i)>126)
            throw new CommandFailure("unsupported_text_characters",
                    "This TV's custom keyboard supports printable ASCII through ADB. Use letters, numbers and punctuation.");
        List<String> chunks=new ArrayList<>();
        int start=0,found;
        // Android 'input text' expands literal %s to a space. Split between % and s to preserve it.
        while((found=text.indexOf("%s",start))>=0) { chunks.add(text.substring(start,found+1));start=found+1; }
        chunks.add(text.substring(start));
        StringJoiner commands=new StringJoiner(" && ");
        for(String chunk:chunks) if(!chunk.isEmpty()) commands.add("/system/bin/input text "+quote(chunk));
        return finish(commands.toString());
    }
    private static String finish(String command) {
        return command+"; result=$?; printf '\\nTVMQTT_RC:%s\\n' \"$result\"";
    }
    static String quote(String value) { return "'"+value.replace("'","'\\''")+"'"; }
    static PrivateKey loadKey(File file) throws Exception {
        if(!file.isFile() || file.length()>16384) throw new CommandFailure("adb_key_missing","Install the already-authorized ADB private key for TV-local text entry.");
        try {
            String pem=new String(Files.readAllBytes(file.toPath()),StandardCharsets.US_ASCII)
                    .replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----","").replaceAll("\\s","");
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        } catch(Exception error) { throw new CommandFailure("adb_key_invalid","Text-entry ADB key must be a PKCS#8 RSA private key."); }
    }
    static byte[] sign(PrivateKey key,byte[] token) throws Exception {
        // AUTH supplies an already-hashed SHA-1 token; signing SHA1(token) would double-hash it.
        byte[] prefix={0x30,0x21,0x30,0x09,0x06,0x05,0x2b,0x0e,0x03,0x02,0x1a,0x05,0x00,0x04,0x14};
        byte[] digest=Arrays.copyOf(prefix,prefix.length+token.length);
        System.arraycopy(token,0,digest,prefix.length,token.length);
        Cipher rsa=Cipher.getInstance("RSA/ECB/PKCS1Padding");rsa.init(Cipher.ENCRYPT_MODE,key);return rsa.doFinal(digest);
    }
    static final class Packet {
        final int code,arg0,arg1;final byte[] data;
        Packet(int code,int arg0,int arg1,byte[] data) { this.code=code;this.arg0=arg0;this.arg1=arg1;this.data=data; }
    }
    static void write(OutputStream out,int code,int a,int b,byte[] data) throws IOException {
        int sum=0;for(byte value:data) sum+=value&255;
        out.write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(code).putInt(a).putInt(b).putInt(data.length).putInt(sum).putInt(code^0xffffffff).array());
        out.write(data);out.flush();
    }
    static Packet read(InputStream input) throws IOException {
        DataInputStream in=new DataInputStream(input);byte[] header=new byte[24];in.readFully(header);
        ByteBuffer h=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int code=h.getInt(),a=h.getInt(),b=h.getInt(),length=h.getInt(),checksum=h.getInt(),magic=h.getInt();
        if(magic!=(code^0xffffffff) || length<0 || length>65536) throw new IOException("Invalid ADB packet");
        byte[] data=new byte[length];in.readFully(data);int sum=0;for(byte value:data) sum+=value&255;
        if(sum!=checksum) throw new IOException("Invalid ADB checksum");
        return new Packet(code,a,b,data);
    }
}
