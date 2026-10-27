package net.phoxis.tvmqtt;

import org.json.*;

public final class Availability {
    private Availability() {}
    public static JSONObject message(AppConfig config,boolean online,String reason,JSONObject snapshot,
                                     String session,String registeredAt,boolean will) throws Exception {
        JSONObject network=snapshot==null?null:snapshot.optJSONObject("network");
        JSONObject ips=network==null?null:network.optJSONObject("ips");
        String update=snapshot==null?null:snapshot.optString("timestamp",null);
        return Jsons.object("schema_version",2,"device_id",config.clientId,"online",online,
                "status",online?"Online":"Offline","reason",reason,"timestamp",will?null:Jsons.now(),
                "last_update",update,"last_ips",ips==null?new JSONObject():Jsons.copy(ips),
                "primary_ip",network==null?null:network.opt("primary_ip"),
                "active_interface",network==null?null:network.opt("active_interface"),
                "session_id",session,"will_registered_at",registeredAt,
                "metadata_scope",will?"will_registration":"latest_state",
                "stale_after_seconds",config.staleSeconds());
    }
}
