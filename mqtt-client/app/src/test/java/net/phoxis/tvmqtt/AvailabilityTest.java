package net.phoxis.tvmqtt;
import org.junit.*;
import org.json.*;
import java.time.Instant;
import static org.junit.Assert.*;

public class AvailabilityTest {
    private AppConfig config() throws Exception {
        return new AppConfig(Jsons.object("mqtt",Jsons.object("uri","mqtt://localhost:1883","clientId","tv-one",
                "baseTopic","tvs/one","password","MQTT_TOP_SECRET"),"tv",
                Jsons.object("driver","sony-bravia","baseUrl","http://127.0.0.1","preSharedKey","TV_TOP_SECRET"),
                "api",Jsons.object("passkey","API_TOP_SECRET")));
    }
    private JSONObject snapshot() throws Exception {
        return Jsons.object("timestamp","2026-10-26T10:00:00Z","network",Jsons.object("primary_ip","192.168.1.50",
                "active_interface","wlan0","ips",Jsons.object("wlan0",new JSONArray().put("192.168.1.50"))));
    }
    @Test public void willUsesClearlyLabelledRegistrationMetadataAndUnknownFailureTime() throws Exception {
        JSONObject data=Availability.message(config(),false,"connection_lost",snapshot(),"session","2026-10-26T10:00:01Z",true);
        assertFalse(data.getBoolean("online"));
        assertTrue(data.isNull("timestamp"));
        assertEquals("will_registration",data.getString("metadata_scope"));
        assertEquals("2026-10-26T10:00:00Z",data.getString("last_update"));
        assertEquals("192.168.1.50",data.getJSONObject("last_ips").getJSONArray("wlan0").getString(0));
    }
    @Test public void onlineAndGracefulOfflineHaveActualPublishTimestamps() throws Exception {
        JSONObject online=Availability.message(config(),true,"state_updated",snapshot(),"s","registered",false);
        assertTrue(online.getBoolean("online")); Instant.parse(online.getString("timestamp"));
        assertEquals("latest_state",online.getString("metadata_scope"));
        JSONObject stopped=Availability.message(config(),false,"stopped",snapshot(),"s","registered",false);
        assertFalse(stopped.getBoolean("online")); Instant.parse(stopped.getString("timestamp"));
        assertEquals("stopped",stopped.getString("reason"));
    }
    @Test public void availabilityNeverContainsAnyCredentialsAndCopiesIps() throws Exception {
        JSONObject source=snapshot();
        JSONObject data=Availability.message(config(),false,"connection_lost",source,"s","r",true);
        source.getJSONObject("network").getJSONObject("ips").put("wlan0",new JSONArray().put("changed"));
        assertEquals("192.168.1.50",data.getJSONObject("last_ips").getJSONArray("wlan0").getString(0));
        for(String secret:new String[]{"MQTT_TOP_SECRET","TV_TOP_SECRET","API_TOP_SECRET"}) assertFalse(data.toString().contains(secret));
    }
}
