package net.phoxis.tvmqtt;

import android.media.session.PlaybackState;
import org.json.JSONObject;
import java.util.*;

/** Select one session and issue an explicit transport command, never a global media-key broadcast. */
final class MediaPlaybackControl {
    interface Session {
        String packageName();
        int state();
        long actions();
        void send(String action) throws Exception;
    }
    static final List<String> KEYS=Collections.unmodifiableList(Arrays.asList(
            "play","pause","stop","rewind","fast_forward","next","previous"));
    static boolean supported(String key) { return KEYS.contains(key); }
    private static int rank(Session s) {
        switch(s.state()) {
            case PlaybackState.STATE_PLAYING: case PlaybackState.STATE_FAST_FORWARDING:
            case PlaybackState.STATE_REWINDING: return 3;
            case PlaybackState.STATE_BUFFERING: case PlaybackState.STATE_CONNECTING:
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS: case PlaybackState.STATE_SKIPPING_TO_NEXT:
            case PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM: return 2;
            case PlaybackState.STATE_PAUSED: case PlaybackState.STATE_STOPPED: return 1;
            default:return 0;
        }
    }
    static Session select(List<? extends Session> sessions,String foreground) {
        Session focused=null,other=null;
        for(Session s:sessions) {
            if(rank(s)==0) continue; // An idle/released/error session is not a loaded player.
            if(s.packageName().equals(foreground)) {
                if(focused==null || rank(s)>rank(focused)) focused=s;
            } else if(other==null || rank(s)>rank(other)) other=s;
        }
        return focused!=null?focused:other;
    }
    static long required(String action) throws CommandFailure {
        switch(action) {
            case "play":return PlaybackState.ACTION_PLAY;
            case "pause":return PlaybackState.ACTION_PAUSE;
            case "stop":return PlaybackState.ACTION_STOP;
            case "rewind":return PlaybackState.ACTION_REWIND;
            case "fast_forward":return PlaybackState.ACTION_FAST_FORWARD;
            case "next":return PlaybackState.ACTION_SKIP_TO_NEXT;
            case "previous":return PlaybackState.ACTION_SKIP_TO_PREVIOUS;
            default:throw new CommandFailure("invalid_value","Unknown playback command");
        }
    }
    static JSONObject execute(String action,List<? extends Session> sessions,String foreground) throws Exception {
        long required=required(action);
        Session selected=select(sessions,foreground);
        if(selected==null) throw new CommandFailure("media_session_unavailable",
                "Open a video or music in the TV app first. No loaded playback session is available.");
        boolean already=(action.equals("play") && selected.state()==PlaybackState.STATE_PLAYING)
                || (action.equals("pause") && selected.state()==PlaybackState.STATE_PAUSED);
        if(!already && (selected.actions()&required)==0) throw new CommandFailure("unsupported_media_command",
                "The current player does not advertise support for "+action+".");
        // Never try a second path after dispatch: a delayed first request could otherwise act twice.
        if(!already) selected.send(action);
        return Jsons.object("accepted",true,"method","android_media_session","key",action,
                "package",selected.packageName(),"already_in_state",already);
    }
}
