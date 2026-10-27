package net.phoxis.tvmqtt;
import org.json.*;

public final class StateChanges {
    private StateChanges() {}
    public static boolean changed(JSONObject previous,JSONObject current,int memoryThresholdMb) throws Exception {
        if(previous==null) return true;
        JSONObject a=Jsons.copy(previous),b=Jsons.copy(current);
        for(String key:new String[]{"timestamp","expires_at","uptime_seconds"}) { a.remove(key); b.remove(key); }
        JSONObject am=a.optJSONObject("memory"),bm=b.optJSONObject("memory");
        if(am!=null && bm!=null) {
            long delta=Math.abs(am.optLong("available_bytes")-bm.optLong("available_bytes"));
            if(delta>=memoryThresholdMb*1024L*1024L) return true;
            for(String key:new String[]{"available_bytes","available_mb","app_heap_used_bytes"}) { am.remove(key); bm.remove(key); }
        }
        cleanCounters(a); cleanCounters(b);
        JSONObject an=a.optJSONObject("network"),bn=b.optJSONObject("network");
        JSONObject aw=an==null?null:an.optJSONObject("wifi"),bw=bn==null?null:bn.optJSONObject("wifi");
        if(aw!=null && bw!=null && aw.opt("rssi_dbm") instanceof Number && bw.opt("rssi_dbm") instanceof Number &&
                Math.abs(aw.getInt("rssi_dbm")-bw.getInt("rssi_dbm"))<5) {
            aw.remove("rssi_dbm"); aw.remove("signal_percent");
            bw.remove("rssi_dbm"); bw.remove("signal_percent");
        }
        return !Jsons.canonical(a).equals(Jsons.canonical(b));
    }
    private static void cleanCounters(JSONObject state) {
        JSONObject network=state.optJSONObject("network");
        if(network!=null) network.remove("traffic");
        JSONObject system=state.optJSONObject("system");
        if(system!=null) system.remove("awake_seconds");
        JSONObject application=state.optJSONObject("application");
        if(application!=null) { application.remove("uptime_seconds"); application.remove("cpu_time_ms"); }
        JSONObject storage=state.optJSONObject("storage");
        if(storage!=null) for(String key:new String[]{"available_bytes","free_bytes","available_mb"}) storage.remove(key);
    }
}
