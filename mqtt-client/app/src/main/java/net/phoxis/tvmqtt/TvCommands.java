package net.phoxis.tvmqtt;

import org.json.*;
import java.util.*;

public final class TvCommands implements CommandProcessor.Runner {
    public interface Snapshot { JSONObject get() throws Exception; }
    private final SonyApi sony;
    private final Snapshot snapshot;
    private final TextEntry textEntry;
    private static final Map<String,String> KEYS = new HashMap<>();
    static {
        String[][] keys={{"up","Up"},{"down","Down"},{"left","Left"},{"right","Right"},{"ok","Confirm"},
            {"back","Return"},{"home","Home"},{"input","Input"},{"play","Play"},{"pause","Pause"},
            {"stop","Stop"},{"rewind","Rewind"},{"fast_forward","Forward"},{"next","Next"},{"previous","Prev"},
            {"options","Options"},{"info","Display"},{"channel_up","ChannelUp"},{"channel_down","ChannelDown"},{"power_off","PowerOff"}};
        for(String[] key:keys) KEYS.put(key[0],key[1]);
    }
    public TvCommands(SonyApi sony,Snapshot snapshot) { this(sony,snapshot,null); }
    public TvCommands(SonyApi sony,Snapshot snapshot,TextEntry.Writer androidText) {
        this.sony=sony;this.snapshot=snapshot;this.textEntry=new TextEntry(sony,androidText);
    }
    public JSONObject execute(JSONObject command) throws Exception {
        String action=command.getString("action");
        switch(action) {
            case "status": case "status_refresh": return snapshot.get();
            case "apps": return sony.applications();
            case "power_on": case "power_off":
                sony.call("system","setPowerStatus",new JSONArray().put(Jsons.object("status","power_on".equals(action))));
                return Jsons.object("requested_power","power_on".equals(action)?"on":"off");
            case "volume_set":
                Object value=command.opt("value");
                if(!(value instanceof Number) || ((Number)value).doubleValue()!=((Number)value).intValue() ||
                        ((Number)value).intValue()<0 || ((Number)value).intValue()>100)
                    throw new CommandFailure("invalid_value","volume_set requires an integer from 0 to 100");
                setVolume(String.valueOf(((Number)value).intValue()));
                return Jsons.object("requested_volume",value);
            case "volume_up": case "volume_down":
                setVolume("volume_up".equals(action)?"+1":"-1"); return Jsons.object("accepted",true);
            case "mute_on": case "mute_off": case "mute":
                boolean mute="mute".equals(action)?!sony.speaker().getBoolean("mute"):"mute_on".equals(action);
                sony.call("audio","setAudioMute",new JSONArray().put(Jsons.object("status",mute)));
                return Jsons.object("requested_mute",mute);
            case "input":
                String input=stringValue(command,128);
                if(input.matches("hdmi[1-4]")) input="extInput:hdmi?port="+input.substring(4);
                if(!input.matches("extInput:(hdmi|composite|component|widi)\\?port=[1-9][0-9]?"))
                    throw new CommandFailure("invalid_value","input requires hdmi1..hdmi4 or an extInput URI");
                sony.call("avContent","setPlayContent",new JSONArray().put(Jsons.object("uri",input)));
                return Jsons.object("requested_input",input);
            case "launch":
                sony.launch(stringValue(command,512)); return Jsons.object("accepted",true);
            case "select_all": return textEntry.selectAll();
            case "backspace":
                Object count=command.opt("value");
                if(count==null || count==JSONObject.NULL) return textEntry.backspace();
                if(!(count instanceof Number) || ((Number)count).doubleValue()!=((Number)count).intValue() ||
                        ((Number)count).intValue()<1 || ((Number)count).intValue()>240)
                    throw new CommandFailure("invalid_value","Backspace count must be an integer from 1 to 240.");
                return textEntry.backspace(((Number)count).intValue());
            case "text":
                return textEntry.send(stringValue(command,240));
            case "key":
                return key(stringValue(command,32));
            default:
                if(KEYS.containsKey(action)) return key(action);
                throw new CommandFailure("unknown_action","Unknown command action");
        }
    }
    private void setVolume(String volume) throws Exception {
        sony.call("audio","setAudioVolume",new JSONArray().put(Jsons.object("target","speaker","volume",volume)));
    }
    private JSONObject key(String name) throws Exception {
        String code=KEYS.get(name);
        if(code==null) throw new CommandFailure("invalid_value","Unknown remote key");
        sony.remoteKey(code);
        return Jsons.object("key",name,"accepted",true);
    }
    private static String stringValue(JSONObject command,int max) throws Exception {
        Object value=command.opt("value");
        if(!(value instanceof String) || ((String)value).isEmpty() || ((String)value).length()>max)
            throw new CommandFailure("invalid_value","This action requires a nonempty string value");
        return (String)value;
    }
}
