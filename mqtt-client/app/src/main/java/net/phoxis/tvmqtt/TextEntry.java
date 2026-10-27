package net.phoxis.tvmqtt;

import org.json.*;

public final class TextEntry {
    public interface Writer {
        JSONObject set(String text) throws Exception;
        default JSONObject selectAll() throws Exception {
            throw new CommandFailure("text_edit_unavailable","TV selection is not configured.");
        }
        default JSONObject backspace(int count) throws Exception {
            if(count==1) return backspace();
            throw new CommandFailure("text_edit_unavailable","Batched TV backspace is not configured.");
        }
        default JSONObject backspace() throws Exception {
            throw new CommandFailure("text_edit_unavailable","TV backspace is not configured.");
        }
    }
    private final SonyApi sony;
    private final Writer android;
    public TextEntry(SonyApi sony,Writer android) { this.sony=sony; this.android=android; }
    public JSONObject selectAll() throws Exception {
        if(android==null) throw new CommandFailure("text_edit_unavailable","TV selection requires Android text access or TV-local ADB.");
        return android.selectAll();
    }
    public JSONObject backspace() throws Exception { return backspace(1); }
    public JSONObject backspace(int count) throws Exception {
        if(android==null) throw new CommandFailure("text_edit_unavailable","TV backspace requires Android text access or TV-local ADB.");
        return android.backspace(count);
    }
    public JSONObject send(String text) throws Exception {
        CommandFailure nativeFailure=null;
        if(android!=null) {
            try { return android.set(text); }
            catch(CommandFailure error) {
                // Fall back only when Android confirms that it did not edit a field.
                if(!error.code.equals("text_access_unavailable") && !error.code.equals("text_not_focused") &&
                        !error.code.equals("text_not_supported")) throw error;
                nativeFailure=error;
            }
        }
        try {
            sony.call("appControl","setTextForm",new JSONArray().put(text));
            return Jsons.object("accepted",true,"method","sony_text_form");
        } catch(CommandFailure error) {
            if(!error.code.equals("sony_7")) throw error;
            if(nativeFailure!=null) throw nativeFailure;
            throw new CommandFailure("text_input_unavailable",
                    "This app does not accept Sony text entry. Enable TV remote text access and focus the search text box.");
        }
    }
}
