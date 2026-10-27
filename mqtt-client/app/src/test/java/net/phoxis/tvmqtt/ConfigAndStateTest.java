package net.phoxis.tvmqtt;
import org.junit.*;
import org.json.*;
import static org.junit.Assert.*;

public class ConfigAndStateTest {
    private JSONObject config(String uri) throws Exception {
        return Jsons.object("mqtt",Jsons.object("uri",uri,"clientId","test-tv","username","example-user","password","secret",
                "baseTopic","tvs/test","tls",Jsons.object("insecureSkipVerify",true)),
                "tv",Jsons.object("driver","sony-bravia","baseUrl","http://127.0.0.1","preSharedKey","private"));
    }
    @Test public void derivesPerTvTopicsAndNormalizesTlsAliases() throws Exception {
        for(String scheme:new String[]{"mqtts","tls","ssl"}) {
            AppConfig c=new AppConfig(config(scheme+"://localhost:8883"));
            assertTrue(c.tls); assertTrue(c.insecureTLS); assertEquals("ssl://localhost:8883",c.uri);
            assertEquals("tvs/test/cmnd/COMMAND",c.commandTopic); assertEquals("tvs/test/stat/RESULT",c.ackTopic);
            assertEquals("tvs/test/tele/STATE",c.stateTopic); assertEquals("tvs/test/tele/LWT",c.availabilityTopic);
        }
    }
    @Test public void supportsPlaintextWithoutInferringTlsFromPort() throws Exception {
        assertFalse(new AppConfig(config("mqtt://localhost:1883")).tls);
        assertFalse(new AppConfig(config("tcp://localhost:8883")).tls);
    }
    @Test public void rejectsWildcardsCredentialsInUriAndTraversal() throws Exception {
        JSONObject c=config("mqtts://localhost:8883"); c.getJSONObject("mqtt").put("baseTopic","tvs/#");
        try { new AppConfig(c); fail(); } catch(IllegalArgumentException expected) {}
        try { new AppConfig(config("mqtts://user:password@localhost:8883")); fail(); } catch(IllegalArgumentException expected) {}
        c=config("mqtts://localhost:8883"); c.getJSONObject("mqtt").getJSONObject("tls").put("caFile","../key");
        try { new AppConfig(c); fail(); } catch(IllegalArgumentException expected) {}
    }
    @Test public void genericTvConfigAndReservedApiPasskeyAreIndependent() throws Exception {
        JSONObject data=config("mqtt://localhost:1883");
        data.put("api",Jsons.object("passkey","reserved-api-password"));
        AppConfig c=new AppConfig(data);
        assertEquals("sony-bravia",c.tvDriver); assertEquals("private",c.tvPsk);
        assertEquals("reserved-api-password",c.apiPasskey);
        assertTrue(c.stateRetain); assertEquals(60,c.heartbeatSeconds); assertEquals(180,c.staleSeconds());
    }
    @Test public void retentionAndHeartbeatCanBeDisabled() throws Exception {
        JSONObject data=config("mqtt://localhost:1883");
        data.put("service",Jsons.object("stateRetain",false,"stateHeartbeatSeconds",0));
        AppConfig c=new AppConfig(data);
        assertFalse(c.stateRetain); assertEquals(0,c.staleSeconds());
    }
    @Test public void legacyControlSectionStillLoadsButUnknownDriverIsRejected() throws Exception {
        JSONObject data=config("mqtt://localhost:1883"); data.remove("tv");
        data.put("sony",Jsons.object("baseUrl","http://127.0.0.1","psk","legacy"));
        assertEquals("legacy",new AppConfig(data).tvPsk);
        data=config("mqtt://localhost:1883"); data.getJSONObject("tv").put("driver","unimplemented");
        try { new AppConfig(data); fail(); } catch(IllegalArgumentException expected) {}
    }
    @Test public void smallMemoryFluctuationsDoNotSpamButVolumeChangesPublish() throws Exception {
        JSONObject a=Jsons.object("power","active","volume",10,"timestamp","old","memory",
                Jsons.object("available_bytes",100*1048576L,"available_mb",100,"total_bytes",1024*1048576L,"low_memory",false));
        JSONObject b=Jsons.copy(a); b.put("timestamp","new");
        b.getJSONObject("memory").put("available_bytes",101*1048576L).put("available_mb",101);
        assertFalse(StateChanges.changed(a,b,8));
        b.put("volume",11); assertTrue(StateChanges.changed(a,b,8));
        b.put("volume",10); b.getJSONObject("memory").put("available_bytes",110*1048576L);
        assertTrue(StateChanges.changed(a,b,8));
    }
}
