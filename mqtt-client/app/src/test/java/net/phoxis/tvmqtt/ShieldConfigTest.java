package net.phoxis.tvmqtt;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ShieldConfigTest {
    private JSONObject shield() throws Exception {
        return Jsons.object("mqtt",Jsons.object("uri","mqtt://localhost:1883","clientId","shield","baseTopic","tvs/living-room-shield"),
                "tv",Jsons.object("driver","nvidia-shield","adb",Jsons.object("enabled",true,"port",5555,"keyFile","adbkey.pem")));
    }
    @Test public void shieldNeedsNoSonyUrlOrPresharedKey() throws Exception {
        AppConfig c=new AppConfig(shield());
        assertEquals("nvidia-shield",c.tvDriver);assertEquals("",c.tvPsk);assertEquals("",c.tvUrl);
        assertFalse(c.textAdbEnabled);
        assertEquals("tvs/living-room-shield/cmnd/COMMAND",c.commandTopic);
    }
    @Test public void sonyStillRequiresItsOwnControlKey() throws Exception {
        JSONObject c=shield();c.getJSONObject("tv").put("driver","sony-bravia").put("baseUrl","http://127.0.0.1");
        try { new AppConfig(c);fail(); } catch(Exception expected) {}
    }
    @Test public void androidDriverRejectsUnknownNamesAndIgnoresLegacyAdb() throws Exception {
        JSONObject c=shield();c.getJSONObject("tv").put("driver","unsupported");
        try { new AppConfig(c);fail(); } catch(IllegalArgumentException expected) {}
        c=shield();c.getJSONObject("tv").getJSONObject("adb").put("keyFile","../other");
        assertFalse(new AppConfig(c).textAdbEnabled);
        c.getJSONObject("tv").remove("adb");
        assertFalse(new AppConfig(c).textAdbEnabled);
    }
    @Test public void androidPowerKeysAreExplicitWakeAndSleepNotToggle() throws Exception {
        assertEquals(224,AndroidTvKeys.code("power_on"));assertEquals(223,AndroidTvKeys.code("power_off"));
        assertEquals(21,AndroidTvKeys.code("left"));assertEquals(22,AndroidTvKeys.code("right"));
        assertEquals(24,AndroidTvKeys.code("volume_up"));assertEquals(25,AndroidTvKeys.code("volume_down"));
        assertFalse(AndroidTvKeys.supported("arbitrary_shell"));
        try { AndroidTvKeys.code("arbitrary_shell");fail(); } catch(CommandFailure expected) {}
    }
    @Test public void shieldDeletionCountsAreBounded() throws Exception {
        assertEquals(1,ShieldDriver.backspaceCount(new JSONObject()));
        assertEquals(12,ShieldDriver.backspaceCount(Jsons.object("value",12)));
        for(Object value:new Object[]{0,241,1.5,"10"}) {
            try { ShieldDriver.backspaceCount(Jsons.object("value",value));fail(); }
            catch(CommandFailure expected) { assertEquals("invalid_value",expected.code); }
        }
    }
}
