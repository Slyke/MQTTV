package net.phoxis.tvmqtt;
import org.json.JSONObject;

/** Routes relative keys through the OS's configured TV/receiver control, or explicit PCM mode. */
final class ShieldVolumeControl {
    interface Audio {
        void suggested(int direction,int flags);
        void music(int direction,int flags);
        void percent(int value) throws Exception;
    }
    // AOSP AudioManager FLAG_SHOW_UI and FLAG_FROM_KEY (1 << 12).
    // The public adjustSuggestedStreamVolume API passes these to the system volume router.
    static final int SYSTEM_KEY_FLAGS=1|(1<<12);
    private final Audio audio;
    final boolean external;
    ShieldVolumeControl(String mode,Audio audio) { external=!mode.equals("device");this.audio=audio; }
    boolean canRequest(boolean localOutputFixed) { return external || !localOutputFixed; }
    String support(boolean localOutputFixed) { return external?"unknown":localOutputFixed?"unavailable":"available"; }
    JSONObject key(String action) throws Exception { return key(action,false); }
    JSONObject key(String action,boolean localOutputFixed) throws Exception {
        int direction;
        switch(action) {
            case "volume_up":direction=1;break;
            case "volume_down":direction=-1;break;
            case "mute":direction=101;break;
            default:throw new CommandFailure("invalid_value","Unknown volume key");
        }
        if(!canRequest(localOutputFixed)) throw new CommandFailure("fixed_volume","The Shield's local audio output uses fixed volume.");
        if(external) audio.suggested(direction,SYSTEM_KEY_FLAGS);else audio.music(direction,1);
        return Jsons.object("accepted",true,"method",external?"android_system_volume":"android_device_volume",
                "key",action,"volume_scope",external?"external":"device","volume_readback",!external,"volume_support",support(localOutputFixed));
    }
    JSONObject set(Object requested) throws Exception {
        if(!(requested instanceof Number) || ((Number)requested).doubleValue()!=((Number)requested).intValue()
                || ((Number)requested).intValue()<0 || ((Number)requested).intValue()>100)
            throw new CommandFailure("invalid_value","volume_set requires an integer from 0 to 100");
        if(external) throw new CommandFailure("external_volume_unavailable",
                "The Shield cannot read or set an absolute TV/receiver volume. Use volume_up, volume_down or mute.");
        audio.percent(((Number)requested).intValue());
        return Jsons.object("requested_volume",requested,"volume_scope","device");
    }
}
