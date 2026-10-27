package net.phoxis.tvmqtt;
import org.junit.*;
import org.json.*;
import static org.junit.Assert.*;

public class NetworkStateTest {
    @Test public void discoversPerInterfaceAddresses() throws Exception {
        JSONObject snapshot=NetworkAddresses.snapshot();
        assertTrue(snapshot.getJSONObject("interfaces").length()>0);
        assertTrue(snapshot.getJSONObject("ips").length()>0);
        assertNotNull(snapshot.getJSONObject("interfaces").optJSONObject("lo"));
    }
    @Test public void primaryAddressPrefersActiveInterfaceAndIpv4() throws Exception {
        JSONObject ips=Jsons.object("lo",new JSONArray().put("127.0.0.1"),"wlan0",
                new JSONArray().put("fe80::1%wlan0").put("192.168.1.50"),"eth0",new JSONArray().put("192.168.2.10"));
        assertEquals("192.168.1.50",NetworkAddresses.primaryIp(ips,"wlan0"));
        assertEquals("192.168.2.10",NetworkAddresses.primaryIp(ips,"eth0"));
        assertNull(NetworkAddresses.primaryIp(Jsons.object("lo",new JSONArray().put("127.0.0.1")),"lo"));
    }
    private JSONObject state() throws Exception {
        return Jsons.object("network",Jsons.object("ips",Jsons.object("wlan0",new JSONArray().put("192.168.1.50")),
                "wifi",Jsons.object("rssi_dbm",-60,"signal_percent",80),"traffic",Jsons.object("rx_bytes",100)),
                "application",Jsons.object("uptime_seconds",5,"cpu_time_ms",100),
                "system",Jsons.object("awake_seconds",100),"storage",Jsons.object("available_bytes",10000,"low_storage",false));
    }
    @Test public void trafficUptimeAndSmallRssiChangesDoNotCauseFloods() throws Exception {
        JSONObject a=state(),b=Jsons.copy(a);
        b.getJSONObject("network").getJSONObject("traffic").put("rx_bytes",5000);
        b.getJSONObject("network").getJSONObject("wifi").put("rssi_dbm",-62).put("signal_percent",75);
        b.getJSONObject("application").put("uptime_seconds",10).put("cpu_time_ms",110);
        b.getJSONObject("system").put("awake_seconds",105);
        b.getJSONObject("storage").put("available_bytes",9000);
        b.put("expires_at","changed");
        assertFalse(StateChanges.changed(a,b,8));
    }
    @Test public void ipChangesLargeSignalChangesAndLowStorageDoPublish() throws Exception {
        JSONObject a=state(),b=Jsons.copy(a);
        b.getJSONObject("network").getJSONObject("ips").put("wlan0",new JSONArray().put("192.168.1.51"));
        assertTrue(StateChanges.changed(a,b,8));
        b=Jsons.copy(a); b.getJSONObject("network").getJSONObject("wifi").put("rssi_dbm",-70);
        assertTrue(StateChanges.changed(a,b,8));
        b=Jsons.copy(a); b.getJSONObject("storage").put("low_storage",true);
        assertTrue(StateChanges.changed(a,b,8));
    }
}
