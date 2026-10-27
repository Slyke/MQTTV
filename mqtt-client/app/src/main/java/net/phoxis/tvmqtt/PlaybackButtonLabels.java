package net.phoxis.tvmqtt;
import java.util.*;

/** Exact semantic labels only: never match titles, playback-speed menus, or arbitrary substrings. */
final class PlaybackButtonLabels {
    static Set<String> labels(String action) {
        Set<String> names=new HashSet<>();
        switch(action) {
            case "play": Collections.addAll(names,"play","resume playback");break;
            case "pause": Collections.addAll(names,"pause","pause playback");break;
            case "stop": names.add("stop");break;
            case "rewind": names.add("rewind");break;
            case "fast_forward": Collections.addAll(names,"fast forward","fast-forward");break;
            case "next": Collections.addAll(names,"next track","skip next");break;
            case "previous": Collections.addAll(names,"previous track","skip previous");break;
        }
        return names;
    }
    static String normalize(CharSequence value) { return value==null?"":value.toString().trim().toLowerCase(Locale.ROOT); }
    static boolean matches(Set<String> labels,CharSequence value) { return labels.contains(normalize(value)); }
    static String opposite(String action) { return action.equals("play")?"pause":action.equals("pause")?"play":""; }
    static String resource(String action) {
        switch(action) {
            case "next":return "lb_playback_controls_skip_next";
            case "previous":return "lb_playback_controls_skip_previous";
            default:return "lb_playback_controls_"+action;
        }
    }
}
