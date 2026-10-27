package net.phoxis.tvmqtt;
import org.json.JSONObject;
import java.io.File;

public final class AndroidTextInput implements TextEntry.Writer {
    private final LocalAdbText adb;
    private final String apkPath;

    public AndroidTextInput(AppConfig config,File directory,String apkPath) {
        this.apkPath=apkPath;
        adb=config.textAdbEnabled?new LocalAdbText(config.textAdbPort,new File(directory,config.textAdbKeyFile)):null;
    }
    public JSONObject set(String text) throws Exception {
        try { return RemoteTextService.setText(text); }
        catch(CommandFailure error) {
            if(adb==null || (!error.code.equals("text_access_unavailable") &&
                    !error.code.equals("text_not_focused") && !error.code.equals("text_not_supported"))) throw error;
            return adb.send(text);
        }
    }
    public JSONObject backspace() throws Exception { return backspace(1); }
    public JSONObject backspace(int count) throws Exception {
        try { return RemoteTextService.backspace(count); }
        catch(CommandFailure error) {
            if(adb==null || (!error.code.equals("text_access_unavailable") &&
                    !error.code.equals("text_not_focused") && !error.code.equals("text_not_supported"))) throw error;
            return adb.backspace(count);
        }
    }
    public JSONObject selectAll() throws Exception {
        try { return RemoteTextService.selectAll(); }
        catch(CommandFailure error) {
            if(adb==null || (!error.code.equals("text_access_unavailable") &&
                    !error.code.equals("text_not_focused") && !error.code.equals("text_not_supported"))) throw error;
            return adb.selectAll(apkPath);
        }
    }
}
