package net.phoxis.tvmqtt;

import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

final class ShieldMediaControl {
    private final Context context;
    ShieldMediaControl(Context context) { this.context=context.getApplicationContext(); }
    private List<MediaPlaybackControl.Session> sessions() throws CommandFailure {
        try {
            MediaSessionManager manager=(MediaSessionManager)context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaPlaybackControl.Session> result=new ArrayList<>();
            for(MediaController controller:manager.getActiveSessions(new ComponentName(context,MediaAccessService.class))) {
                PlaybackState state=controller.getPlaybackState();
                if(state==null) continue;
                result.add(new MediaPlaybackControl.Session() {
                    public String packageName() { return controller.getPackageName(); }
                    public int state() { return state.getState(); }
                    public long actions() { return state.getActions(); }
                    public void send(String action) {
                        MediaController.TransportControls controls=controller.getTransportControls();
                        switch(action) {
                            case "play":controls.play();break;
                            case "pause":controls.pause();break;
                            case "stop":controls.stop();break;
                            case "rewind":controls.rewind();break;
                            case "fast_forward":controls.fastForward();break;
                            case "next":controls.skipToNext();break;
                            case "previous":controls.skipToPrevious();break;
                            default:throw new IllegalArgumentException("Unknown playback command");
                        }
                    }
                });
            }
            return result;
        } catch(SecurityException e) {
            throw new CommandFailure("media_access_unavailable",
                    "Enable playback control & media reporting in Advanced options (notification access).");
        }
    }
    JSONObject execute(String action) throws Exception {
        List<MediaPlaybackControl.Session> players;
        try { players=sessions(); }
        catch(CommandFailure e) { return RemoteTextService.playback(action); }
        String foreground=RemoteTextService.currentPackage();
        MediaPlaybackControl.Session selected=MediaPlaybackControl.select(players,foreground);
        // An idle foreground app may show real playback controls while publishing stale session data.
        boolean ownsSession=false;
        for(MediaPlaybackControl.Session player:players) if(player.packageName().equals(foreground)) ownsSession=true;
        if(selected==null || (ownsSession && !selected.packageName().equals(foreground)))
            return RemoteTextService.playback(action);
        try { return MediaPlaybackControl.execute(action,players,foreground); }
        catch(CommandFailure e) {
            // This error is raised before dispatch. Never try another route after a send.
            if(e.code.equals("unsupported_media_command")) return RemoteTextService.playback(action);
            throw e;
        } catch(SecurityException e) {
            throw new CommandFailure("media_access_unavailable","Android rejected access to this playback session.");
        }
    }
    JSONObject snapshot() throws Exception {
        JSONObject result=sessionSnapshot();
        result.put("fallback","android_accessibility_playback").put("accessibility_ready",RemoteTextService.ready());
        return result;
    }
    private JSONObject sessionSnapshot() throws Exception {
        try {
            MediaPlaybackControl.Session selected=MediaPlaybackControl.select(sessions(),RemoteTextService.currentPackage());
            if(selected==null) return Jsons.object("method","android_media_session","ready",false,"reason","media_session_unavailable");
            JSONArray supported=new JSONArray();
            for(String key:MediaPlaybackControl.KEYS)
                if((selected.actions()&MediaPlaybackControl.required(key))!=0) supported.put(key);
            return Jsons.object("method","android_media_session","ready",true,"package",selected.packageName(),"supported",supported);
        } catch(CommandFailure e) {
            return Jsons.object("method","android_media_session","ready",false,"reason",e.code);
        }
    }
}
