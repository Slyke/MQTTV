package net.phoxis.tvmqtt;
import org.junit.Test;
import static org.junit.Assert.*;
public class ShieldVolumeControlTest {
    static class Recording implements ShieldVolumeControl.Audio {
        String route;int direction,flags,percent=-1;
        public void suggested(int direction,int flags) { route="system";this.direction=direction;this.flags=flags; }
        public void music(int direction,int flags) { route="device";this.direction=direction;this.flags=flags; }
        public void percent(int value) { percent=value; }
    }
    @Test public void systemKeysUseSuggestedHardwareVolumeAndDoNotSetInternalPercent() throws Exception {
        Recording audio=new Recording();ShieldVolumeControl volume=new ShieldVolumeControl("system",audio);
        assertTrue(volume.external);assertTrue(volume.key("volume_down").getBoolean("accepted"));
        assertEquals("system",audio.route);assertEquals(-1,audio.direction);
        assertEquals(4097,audio.flags);assertEquals(-1,audio.percent);
        assertFalse(volume.key("mute").getBoolean("volume_readback"));assertEquals(101,audio.direction);
    }
    @Test public void externalAbsoluteVolumeIsRejectedWithoutChangingAudio() throws Exception {
        Recording audio=new Recording();ShieldVolumeControl volume=new ShieldVolumeControl("system",audio);
        try { volume.set(5);fail(); } catch(CommandFailure e) { assertEquals("external_volume_unavailable",e.code); }
        assertNull(audio.route);assertEquals(-1,audio.percent);
    }
    @Test public void explicitDeviceModeRetainsPcmAdjustmentAndAbsoluteVolume() throws Exception {
        Recording audio=new Recording();ShieldVolumeControl volume=new ShieldVolumeControl("device",audio);
        assertFalse(volume.external);volume.key("volume_up");assertEquals("device",audio.route);
        assertEquals(1,audio.direction);volume.set(5);assertEquals(5,audio.percent);
        for(Object value:new Object[]{-1,101,1.5,"5"}) {
            try { volume.set(value);fail(); } catch(CommandFailure e) { assertEquals("invalid_value",e.code); }
        }
    }
    @Test public void externalSupportStaysUnknownEvenWhenTheLocalStreamIsFixed() throws Exception {
        Recording audio=new Recording();ShieldVolumeControl volume=new ShieldVolumeControl("system",audio);
        assertTrue(volume.canRequest(true));assertEquals("unknown",volume.support(true));
        assertEquals("unknown",volume.key("volume_down",true).getString("volume_support"));
        assertEquals("system",audio.route);assertEquals(-1,audio.direction);
    }
    @Test public void onlyAnExplicitlyFixedLocalOutputBlocksPcmRequests() throws Exception {
        Recording audio=new Recording();ShieldVolumeControl volume=new ShieldVolumeControl("device",audio);
        assertFalse(volume.canRequest(true));assertEquals("unavailable",volume.support(true));
        try {volume.key("volume_down",true);fail();}
        catch(CommandFailure e){assertEquals("fixed_volume",e.code);}
        assertNull(audio.route);assertEquals(-1,audio.percent);
    }
}
