package net.phoxis.tvmqtt;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SettingsConfigTest {
    private JSONObject profile() throws Exception {
        return Jsons.object("mqtt",Jsons.object("uri","mqtts://broker:8883","clientId","test-tv","baseTopic","tvs/existing",
                "password","fixture-broker-password","tls",Jsons.object("caCertificatePem","fixture-ca","insecureSkipVerify",true)),
                "tv",Jsons.object("driver","nvidia-shield","adb",Jsons.object("enabled",true,"keyFile","authorized.pem")),
                "api",Jsons.object("passkey","fixture-api-password","maxConnections",24),
                "service",Jsons.object("statePollSeconds",9,"keepCpuAwake",false),
                "future",Jsons.object("custom",true));
    }
    @Test public void absentNullAndBlankTopicUseTheActualDeviceName() throws Exception {
        for(Object value:new Object[]{null,JSONObject.NULL,"","  "}) {
            JSONObject config=profile();
            if(value==null) config.getJSONObject("mqtt").remove("baseTopic");
            else config.getJSONObject("mqtt").put("baseTopic",value);
            AppConfig parsed=new AppConfig(config,"Living Room TV");
            assertEquals("tvs/living-room-tv",parsed.baseTopic);
            assertEquals("tvs/living-room-tv/cmnd/COMMAND",parsed.commandTopic);
        }
    }
    @Test public void explicitTopicAcceptsTrailingSlashWithoutEmptySegments() throws Exception {
        JSONObject config=profile();config.getJSONObject("mqtt").put("baseTopic","tvs/custom/");
        AppConfig parsed=new AppConfig(config,"Different TV");
        assertEquals("tvs/custom",parsed.baseTopic);
        assertEquals("tvs/custom/stat/RESULT",parsed.ackTopic);
    }
    @Test public void deviceNamesCannotInjectTopicLevelsOrWildcards() {
        assertEquals("living-room-shield",DeviceTopics.slug("Living R\u00f3om / SHIELD+#"));
        assertEquals("android-tv",DeviceTopics.slug(" /#+ "));
        assertTrue(DeviceTopics.slug(new String(new char[100]).replace('\0','a')).length()<=64);
        for(String invalid:new String[]{"/tvs/name","tvs/#","tvs/+","/","tvs/\0name"}) {
            try { DeviceTopics.base(invalid,"TV");fail(); }
            catch(IllegalArgumentException expected) {}
        }
    }
    @Test public void editedFieldsPreserveSecretsAdvancedSettingsAndAuthorizedKeys() throws Exception {
        JSONObject original=profile();
        JSONObject draft=ConfigEditor.merge(original,Jsons.object("mqtt",Jsons.object("baseTopic","tvs/changed"),
                "api",Jsons.object("https",Jsons.object("enabled",true,"port",8789))));
        AppConfig parsed=new AppConfig(draft);
        assertEquals("fixture-broker-password",parsed.password);
        assertEquals("fixture-api-password",parsed.apiPasskey);
        assertEquals("fixture-ca",parsed.caPem);
        assertEquals("authorized.pem",draft.getJSONObject("tv").getJSONObject("adb").getString("keyFile"));
        assertFalse(parsed.textAdbEnabled);
        assertEquals(24,parsed.apiMaxConnections);assertEquals(9,parsed.pollSeconds);
        assertFalse(parsed.keepCpuAwake);assertTrue(draft.getJSONObject("future").getBoolean("custom"));
        assertEquals("tvs/existing",original.getJSONObject("mqtt").getString("baseTopic"));
    }
    @Test public void legacyConfigCanBeEditedWithoutDiscardingDriverCredentials() throws Exception {
        JSONObject old=profile();old.remove("tv");
        old.put("sony",Jsons.object("baseUrl","http://127.0.0.1","psk","fixture-control-key",
                "textAdb",Jsons.object("enabled",true,"port",5555,"keyFile","existing.pem")));
        JSONObject draft=ConfigEditor.initial(old,"new-client","nvidia-shield");
        AppConfig parsed=new AppConfig(draft);
        assertEquals("sony-bravia",parsed.tvDriver);assertEquals("fixture-control-key",parsed.tvPsk);
        assertEquals("existing.pem",parsed.textAdbKeyFile);
        draft.getJSONObject("tv").put("psk","edited");
        assertEquals("fixture-control-key",old.getJSONObject("sony").getString("psk"));
    }
    @Test public void newSetupHasEditableDefaultsButDoesNotInventBrokerCredentials() throws Exception {
        JSONObject draft=ConfigEditor.initial(null,"tv-bedroom-123","nvidia-shield");
        assertEquals("tv-bedroom-123",draft.getJSONObject("mqtt").getString("clientId"));
        assertEquals("",draft.getJSONObject("mqtt").getString("uri"));
        assertEquals("",draft.getJSONObject("mqtt").getString("baseTopic"));
        assertFalse(draft.getJSONObject("mqtt").has("password"));
        draft.getJSONObject("mqtt").put("uri","mqtt://broker:1883");
        assertEquals("tvs/bedroom",new AppConfig(draft,"Bedroom").baseTopic);
    }
    @Test public void invalidEditsNeverMutateOriginalConfiguration() throws Exception {
        JSONObject original=profile();
        JSONObject draft=ConfigEditor.merge(original,Jsons.object("api",Jsons.object("http",
                Jsons.object("enabled",true,"port",0))));
        try { new AppConfig(draft);fail(); } catch(IllegalArgumentException expected) {}
        assertFalse(original.getJSONObject("api").has("http"));
    }
}
