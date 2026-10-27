package net.phoxis.tvmqtt;

import android.content.*;
import android.content.pm.*;
import android.media.AudioManager;
import android.os.PowerManager;
import android.os.Build;
import android.provider.Settings;
import org.json.*;
import java.util.*;

public final class ShieldDriver implements java.io.Closeable {
    private final Context context;
    private final AudioManager audio;
    private final ShieldVolumeControl volume;
    private final ShieldMediaControl media;
    private final PowerManager power;
    private final LocalAdbText adb;
    private final AndroidTextInput developerText;
    private final java.io.File adbKey;
    private final int adbPort;
    public ShieldDriver(Context context,AppConfig config) {
        this.context=context.getApplicationContext();
        media=new ShieldMediaControl(this.context);
        audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        power=(PowerManager)context.getSystemService(Context.POWER_SERVICE);
        volume=new ShieldVolumeControl(config.tvVolumeControl,new ShieldVolumeControl.Audio() {
            public void suggested(int direction,int flags) { audio.adjustSuggestedStreamVolume(direction,AudioManager.USE_DEFAULT_STREAM_TYPE,flags); }
            public void music(int direction,int flags) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,direction,flags); }
            public void percent(int value) throws Exception {
                if(audio.isVolumeFixed()) throw new CommandFailure("fixed_volume","Shield output uses fixed volume.");
                int maximum=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                audio.setStreamVolume(AudioManager.STREAM_MUSIC,(int)Math.round(value*maximum/100.0),AudioManager.FLAG_SHOW_UI);
            }
        });
        boolean developer=config.tvControlMethod.equals("adb");
        adbPort=config.textAdbPort;adbKey=new java.io.File(context.getFilesDir(),config.textAdbKeyFile);
        adb=developer?new LocalAdbText(adbPort,adbKey):null;
        developerText=developer?new AndroidTextInput(config,context.getFilesDir(),context.getApplicationInfo().sourceDir):null;
    }
    public JSONObject snapshot() throws Exception {
        boolean on=power.isInteractive(),fixed=audio.isVolumeFixed();
        int level=audio.getStreamVolume(AudioManager.STREAM_MUSIC),maximum=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        boolean developerReady=adb!=null && developerReady();
        boolean volumeKeys=adb!=null?developerReady:volume.canRequest(fixed);
        JSONObject control=adb!=null?Jsons.object("method","android_adb","ready",developerReady,"debugging_required",true,
                "error",developerReady?null:"adb_unavailable"):Jsons.object("method","android_accessibility","ready",RemoteTextService.ready(),
                "debugging_required",false,"current_app",RemoteTextService.currentPackage(),
                "error",RemoteTextService.ready()?null:"control_access_unavailable");
        control.put("developer_options_enabled",settingFlag("development_settings_enabled"))
                .put("usb_debugging_enabled",settingFlag("adb_enabled"))
                .put("network_debugging_enabled",settingFlag("adb_tcp_enabled"));
        return Jsons.object("reachable",true,"driver","nvidia-shield","power",on?"active":"standby","state",on?"on":"off",
                "power_scope","device","volume",volume.external||fixed||maximum==0?null:Math.round(level*100.0/maximum),
                "volume_level",level,"volume_max",maximum,"volume_fixed",fixed,"volume_scope",volume.external?"external":"device",
                "volume_control",volume.external?"system":"device","volume_readback",!volume.external,
                "hdmi_cec_volume_enabled",settingFlag("hdmi_control_volume_control_enabled"),
                "volume_support",volume.support(fixed),
                "volume_control_error",volumeKeys?null:adb!=null?"adb_unavailable":"fixed_volume",
                "device_volume",Jsons.object("level",level,"maximum",maximum,"muted",audio.isStreamMute(AudioManager.STREAM_MUSIC)),
                "muted",volume.external?null:audio.isStreamMute(AudioManager.STREAM_MUSIC),"input",null,"content",null,
                "control",control,"media_control",adb==null?media.snapshot():Jsons.object("method","android_adb","ready",developerReady),
                "capabilities",Jsons.object("navigation",(adb!=null?developerReady:RemoteTextService.ready()),"apps",true,
                        "app_launch",adb!=null?developerReady:launchReady(),"input",false,"power",(adb!=null?developerReady:RemoteTextService.ready()),
                        "volume_set",!volume.external&&!fixed,"volume_keys",volumeKeys,"text",adb!=null?developerReady:RemoteTextService.ready()));
    }
    public JSONObject execute(JSONObject command,TvCommands.Snapshot state) throws Exception {
        String action=command.getString("action");
        switch(action) {
            case "status": case "status_refresh": return state.get();
            case "apps": return applications();
            case "launch": return launch(stringValue(command,512));
            case "input": throw new CommandFailure("unsupported_command","Shield does not expose a TV HDMI input selector.");
            case "text":
                String value=stringValue(command,240);
                if(developerText!=null) return developerText.set(value);
                return RemoteTextService.setText(value);
            case "select_all": return developerText!=null?developerText.selectAll():RemoteTextService.selectAll();
            case "backspace": return developerText==null?RemoteTextService.backspace(backspaceCount(command)):developerText.backspace(backspaceCount(command));
            case "volume_set": return volume.set(command.opt("value"));
            case "mute_on": case "mute_off":
                if(volume.external) throw new CommandFailure("external_volume_unavailable","External mute state cannot be read. Use the mute toggle.");
                if(audio.isVolumeFixed()) throw new CommandFailure("fixed_volume","Explicit mute state is unavailable for fixed HDMI output.");
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,action.equals("mute_on")?AudioManager.ADJUST_MUTE:AudioManager.ADJUST_UNMUTE,0);
                return Jsons.object("requested_mute",action.equals("mute_on"));
            case "key": return key(stringValue(command,32));
            default:
                if(AndroidTvKeys.supported(action)) return key(action);
                throw new CommandFailure("unknown_action","Unknown command action");
        }
    }
    private JSONObject key(String action) throws Exception {
        if(adb!=null) { adb.key(AndroidTvKeys.code(action));return Jsons.object("accepted",true,"key",action,"method","android_adb"); }
        if(action.equals("power_on")) {
            @SuppressWarnings("deprecation") PowerManager.WakeLock wake=power.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK|PowerManager.ACQUIRE_CAUSES_WAKEUP,"tv-mqtt:remote-wake");
            wake.acquire(2000);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(()->{if(wake.isHeld()) wake.release();},1500);
            return Jsons.object("accepted",true,"method","android_power","key",action);
        }
        if(action.equals("volume_up") || action.equals("volume_down") || action.equals("mute")) {
            return volume.key(action,audio.isVolumeFixed());
        }
        if(MediaPlaybackControl.supported(action)) return media.execute(action);
        return RemoteTextService.control(action);
    }
    private JSONObject applications() throws Exception {
        PackageManager packages=context.getPackageManager();
        TreeMap<String,JSONObject> apps=new TreeMap<>();
        for(String category:new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER,Intent.CATEGORY_LAUNCHER}) {
            Intent intent=new Intent(Intent.ACTION_MAIN).addCategory(category);
            for(ResolveInfo item:packages.queryIntentActivities(intent,0)) {
                ActivityInfo activity=item.activityInfo;
                String uri=new ComponentName(activity.packageName,activity.name).flattenToString();
                apps.put(uri,Jsons.object("title",item.loadLabel(packages).toString(),"uri",uri,"package",activity.packageName));
            }
        }
        JSONArray output=new JSONArray();
        for(JSONObject app:apps.values()) { if(output.length()>=80) break;output.put(app); }
        return Jsons.object("apps",output,"total",apps.size());
    }
    private JSONObject launch(String value) throws Exception {
        JSONArray apps=applications().getJSONArray("apps");
        for(int i=0;i<apps.length();i++) {
            JSONObject app=apps.getJSONObject(i);
            if(value.equals(app.getString("uri")) || value.equals(app.getString("package")) || value.equalsIgnoreCase(app.getString("title"))) {
                if(adb!=null) { adb.launch(app.getString("uri"));return Jsons.object("accepted",true,"method","android_adb"); }
                if(!launchReady()) throw new CommandFailure("launch_access_unavailable",
                        "Allow background app launches in Connection settings > Driver, or enable TV remote text access in Accessibility.");
                Intent intent=Intent.makeMainActivity(ComponentName.unflattenFromString(app.getString("uri")));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { context.startActivity(intent); }
                catch(ActivityNotFoundException | SecurityException error) {
                    throw new CommandFailure("app_launch_failed","Android could not open that application. Check the app and background-launch access.");
                }
                return Jsons.object("accepted",true,"method","android_intent");
            }
        }
        throw new CommandFailure("app_not_found","Use an application title, package or URI returned by apps.");
    }
    private boolean launchReady() {
        return Build.VERSION.SDK_INT<29 || Settings.canDrawOverlays(context) || RemoteTextService.ready();
    }
    private Object settingFlag(String name) {
        try { return Settings.Global.getInt(context.getContentResolver(),name,0)!=0; }
        catch(SecurityException e) { return JSONObject.NULL; }
    }
    private boolean developerReady() {
        if(!adbKey.isFile()) return false;
        try(java.net.Socket socket=new java.net.Socket()) { socket.connect(new java.net.InetSocketAddress("127.0.0.1",adbPort),150);return true; }
        catch(Exception e) { return false; }
    }
    public void close() {}
    static int backspaceCount(JSONObject command) throws Exception {
        Object value=command.opt("value");
        if(value==null || value==JSONObject.NULL) return 1;
        if(!(value instanceof Number) || ((Number)value).doubleValue()!=((Number)value).intValue() ||
                ((Number)value).intValue()<1 || ((Number)value).intValue()>240)
            throw new CommandFailure("invalid_value","Backspace count must be an integer from 1 to 240.");
        return ((Number)value).intValue();
    }
    private static String stringValue(JSONObject command,int maximum) throws Exception {
        Object value=command.opt("value");
        if(!(value instanceof String) || ((String)value).isEmpty() || ((String)value).length()>maximum)
            throw new CommandFailure("invalid_value","This action requires a nonempty string value");
        return (String)value;
    }
}
