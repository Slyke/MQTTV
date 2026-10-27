package net.phoxis.tvmqtt;
import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

public final class ConfigStore {
    private ConfigStore() {}
    public static File file(Context context) { return new File(context.getFilesDir(),"config.json"); }
    public static boolean exists(Context context) {
        File file=file(context);
        return file.isFile() || new File(file.getPath()+".bak").isFile();
    }
    public static synchronized JSONObject read(Context context) throws Exception {
        AtomicFile atomic=new AtomicFile(file(context));
        try(FileInputStream in=atomic.openRead();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] block=new byte[4096];int count;
            while((count=in.read(block))!=-1) {
                if(out.size()+count>262144) throw new IOException("Configuration exceeds 256 KiB");
                out.write(block,0,count);
            }
            return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
        }
    }
    public static synchronized AppConfig load(Context context) throws Exception {
        JSONObject root=read(context);
        if(Credentials.ensurePasskey(root)) save(context,root.toString(2).getBytes(StandardCharsets.UTF_8));
        return new AppConfig(root,DeviceName.read(context));
    }
    public static synchronized void save(Context context,byte[] bytes) throws Exception {
        if(bytes.length>262144) throw new IOException("Configuration exceeds 256 KiB");
        JSONObject root=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
        if(Credentials.ensurePasskey(root)) bytes=root.toString(2).getBytes(StandardCharsets.UTF_8);
        if(bytes.length>262144) throw new IOException("Configuration exceeds 256 KiB");
        new AppConfig(root,DeviceName.read(context));
        AtomicFile atomic=new AtomicFile(file(context));
        FileOutputStream out=null;
        try { out=atomic.startWrite(); out.write(bytes); atomic.finishWrite(out); }
        catch(Exception e) { if(out!=null) atomic.failWrite(out); throw e; }
    }
}
