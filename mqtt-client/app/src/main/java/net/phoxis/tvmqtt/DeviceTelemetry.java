package net.phoxis.tvmqtt;

import android.app.*;
import android.app.usage.*;
import android.content.*;
import android.content.pm.*;
import android.media.*;
import android.media.session.*;
import android.os.*;
import org.json.*;
import java.time.Instant;
import java.util.*;

public final class DeviceTelemetry {
    private final Context context;
    private final SystemTelemetry system;
    private long lastUsageQuery;
    private String foreground;
    private long foregroundAt;
    public DeviceTelemetry(Context context) { this.context=context.getApplicationContext(); this.system=new SystemTelemetry(this.context); }
    public JSONObject snapshot() throws Exception {
        ActivityManager manager=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(memory);
        Runtime heap=Runtime.getRuntime();
        JSONObject result = Jsons.object("memory",Jsons.object("available_bytes",memory.availMem,"total_bytes",memory.totalMem,
                "available_mb",Math.round(memory.availMem/1048576.0),"total_mb",Math.round(memory.totalMem/1048576.0),
                "low_memory",memory.lowMemory,"threshold_bytes",memory.threshold,
                "app_heap_used_bytes",heap.totalMemory()-heap.freeMemory()),
                "foreground_app",foregroundApp(),"media",media(),
                "uptime_seconds",SystemClock.elapsedRealtime()/1000);
        result.put("network",NetworkTelemetry.snapshot(context));
        JSONObject extra=system.snapshot();
        Iterator<String> keys=extra.keys();
        while(keys.hasNext()) { String key=keys.next(); result.put(key,extra.get(key)); }
        return result;
    }
    private JSONObject foregroundApp() throws Exception {
        AppOpsManager ops=(AppOpsManager)context.getSystemService(Context.APP_OPS_SERVICE);
        if(ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),context.getPackageName())!=AppOpsManager.MODE_ALLOWED)
            return Jsons.object("available",false,"reason","usage_access_not_granted");
        long now=System.currentTimeMillis();
        UsageStatsManager usage=(UsageStatsManager)context.getSystemService(Context.USAGE_STATS_SERVICE);
        UsageEvents events=usage.queryEvents(lastUsageQuery==0?now-12*60*60*1000:Math.max(0,lastUsageQuery-1000),now);
        if(events==null) return Jsons.object("available",false,"reason","usage_data_unavailable");
        UsageEvents.Event event=new UsageEvents.Event();
        while(events.hasNextEvent()) {
            events.getNextEvent(event);
            if(event.getEventType()==UsageEvents.Event.MOVE_TO_FOREGROUND && event.getTimeStamp()>=foregroundAt) {
                foreground=event.getPackageName(); foregroundAt=event.getTimeStamp();
            }
        }
        lastUsageQuery=now;
        if(foreground==null) return Jsons.object("available",true,"package",null);
        String label=foreground;
        try {
            PackageManager packages=context.getPackageManager();
            label=packages.getApplicationLabel(packages.getApplicationInfo(foreground,0)).toString();
        } catch(PackageManager.NameNotFoundException ignored) {}
        return Jsons.object("available",true,"package",foreground,"label",label,
                "last_foreground_at",Instant.ofEpochMilli(foregroundAt).toString());
    }
    private JSONObject media() throws Exception {
        try {
            MediaSessionManager manager=(MediaSessionManager)context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> sessions=manager.getActiveSessions(new ComponentName(context,MediaAccessService.class));
            if(sessions.isEmpty()) return Jsons.object("available",true,"active",false);
            MediaController chosen=sessions.get(0);
            for(MediaController candidate:sessions) {
                PlaybackState playback=candidate.getPlaybackState();
                if(playback!=null && playback.getState()==PlaybackState.STATE_PLAYING) { chosen=candidate; break; }
            }
            JSONObject result=Jsons.object("available",true,"active",true,"package",chosen.getPackageName());
            PlaybackState playback=chosen.getPlaybackState();
            if(playback!=null) {
                String[] names={"none","stopped","paused","playing","fast_forwarding","rewinding","buffering","error",
                    "connecting","skipping_previous","skipping_next","skipping_queue_item"};
                int state=playback.getState();
                result.put("state",state>=0&&state<names.length?names[state]:"unknown");
                result.put("position_ms",playback.getPosition());
            }
            MediaMetadata metadata=chosen.getMetadata();
            if(metadata!=null) {
                putText(result,"title",metadata.getText(MediaMetadata.METADATA_KEY_TITLE));
                putText(result,"display_title",metadata.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE));
                putText(result,"artist",metadata.getText(MediaMetadata.METADATA_KEY_ARTIST));
                putText(result,"album",metadata.getText(MediaMetadata.METADATA_KEY_ALBUM));
                if(metadata.containsKey(MediaMetadata.METADATA_KEY_DURATION))
                    result.put("duration_ms",metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
            }
            return result;
        } catch(SecurityException ignored) {
            return Jsons.object("available",false,"reason","media_access_not_granted");
        }
    }
    private void putText(JSONObject object,String key,CharSequence value) throws Exception {
        if(value!=null) object.put(key,value.length()>512?value.subSequence(0,512).toString():value.toString());
    }
}
