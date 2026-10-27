package net.phoxis.tvmqtt;

import org.json.*;
import java.util.Iterator;

/** Edits a copy so cancellation/validation cannot alter the saved configuration. */
public final class ConfigEditor {
    private ConfigEditor() {}
    public static JSONObject initial(JSONObject existing,String clientId,String driver) throws Exception {
        JSONObject result=existing==null?new JSONObject():new JSONObject(existing.toString());
        if(!result.has("mqtt")) result.put("mqtt",Jsons.object("uri","","clientId",clientId,"baseTopic","",
                "tls",Jsons.object("insecureSkipVerify",true,"trustSystemCAs",true)));
        if(!result.has("tv")) result.put("tv",result.has("sony")?
                new JSONObject(result.getJSONObject("sony").toString()):
                Jsons.object("driver",driver,"baseUrl","http://127.0.0.1"));
        if(existing==null) result.put("api",Jsons.object("bindAddress","0.0.0.0","http",Jsons.object("enabled",true,"port",8788),"https",Jsons.object("enabled",false,"port",8789)));
        JSONObject tv=result.getJSONObject("tv");
        if(!tv.has("adb") && tv.has("textAdb")) tv.put("adb",new JSONObject(tv.getJSONObject("textAdb").toString()));
        Credentials.ensurePasskey(result);
        return result;
    }
    public static JSONObject merge(JSONObject original,JSONObject edits) throws Exception {
        JSONObject result=new JSONObject(original.toString());
        for(Iterator<String> keys=edits.keys();keys.hasNext();) {
            String key=keys.next();Object value=edits.get(key);
            if(value instanceof JSONObject && result.optJSONObject(key)!=null)
                value=merge(result.getJSONObject(key),(JSONObject)value);
            result.put(key,value);
        }
        return result;
    }
}
