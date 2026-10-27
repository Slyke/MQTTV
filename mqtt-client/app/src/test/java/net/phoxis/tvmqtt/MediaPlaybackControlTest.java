package net.phoxis.tvmqtt;
import android.media.session.PlaybackState;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class MediaPlaybackControlTest {
    static final class Player implements MediaPlaybackControl.Session {
        final String pkg;final int state;final long actions;
        final List<String> sent=new ArrayList<>();boolean fail;
        Player(String pkg,int state,long actions) { this.pkg=pkg;this.state=state;this.actions=actions; }
        public String packageName() { return pkg; }
        public int state() { return state; }
        public long actions() { return actions; }
        public void send(String action) throws Exception {
            sent.add(action);if(fail) throw new CommandFailure("dispatch_failed","uncertain");
        }
    }
    private Player player(String pkg,int state) {
        return new Player(pkg,state,PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_STOP);
    }
    @Test public void pauseAndResumeUseThePlayersExplicitCommands() throws Exception {
        Player playing=player("org.jellyfin.androidtv",PlaybackState.STATE_PLAYING);
        assertEquals("android_media_session",MediaPlaybackControl.execute("pause",Arrays.asList(playing),"").getString("method"));
        assertEquals(Arrays.asList("pause"),playing.sent);
        Player paused=player("org.jellyfin.androidtv",PlaybackState.STATE_PAUSED);
        assertEquals("org.jellyfin.androidtv",MediaPlaybackControl.execute("play",Arrays.asList(paused),"").getString("package"));
        assertEquals(Arrays.asList("play"),paused.sent);
    }
    @Test public void focusedPausedPlayerWinsOverUnrelatedBackgroundPlayback() throws Exception {
        Player background=player("example.music",PlaybackState.STATE_PLAYING),focused=player("example.video",PlaybackState.STATE_PAUSED);
        MediaPlaybackControl.execute("play",Arrays.asList(background,focused),"example.video");
        assertTrue(background.sent.isEmpty());assertEquals(Arrays.asList("play"),focused.sent);
    }
    @Test public void withoutFocusedPlayerPlayingSessionWinsOverStalePausedOne() throws Exception {
        Player paused=player("old.player",PlaybackState.STATE_PAUSED),playing=player("current.player",PlaybackState.STATE_PLAYING);
        MediaPlaybackControl.execute("pause",Arrays.asList(paused,playing),"launcher");
        assertTrue(paused.sent.isEmpty());assertEquals(Arrays.asList("pause"),playing.sent);
    }
    @Test public void explicitPlayOrPauseNeverTogglesAnAlreadyMatchingState() throws Exception {
        Player playing=player("video",PlaybackState.STATE_PLAYING),paused=player("video",PlaybackState.STATE_PAUSED);
        assertTrue(MediaPlaybackControl.execute("play",Arrays.asList(playing),"").getBoolean("already_in_state"));
        assertTrue(MediaPlaybackControl.execute("pause",Arrays.asList(paused),"").getBoolean("already_in_state"));
        assertTrue(playing.sent.isEmpty());assertTrue(paused.sent.isEmpty());
    }
    @Test public void unsupportedCommandDoesNotFallBackToAnotherPlayer() throws Exception {
        Player focused=new Player("video",PlaybackState.STATE_PLAYING,PlaybackState.ACTION_PAUSE);
        Player background=player("music",PlaybackState.STATE_PLAYING);
        try { MediaPlaybackControl.execute("next",Arrays.asList(focused,background),"video");fail(); }
        catch(CommandFailure e) { assertEquals("unsupported_media_command",e.code); }
        assertTrue(focused.sent.isEmpty());assertTrue(background.sent.isEmpty());
    }
    @Test public void idleErrorAndAbsentSessionsReturnAnErrorInsteadOfSuccess() throws Exception {
        List<Player> idle=Arrays.asList(player("idle",PlaybackState.STATE_NONE),player("error",PlaybackState.STATE_ERROR));
        for(List<Player> list:Arrays.asList(idle,Collections.<Player>emptyList())) {
            try { MediaPlaybackControl.execute("play",list,"idle");fail(); }
            catch(CommandFailure e) { assertEquals("media_session_unavailable",e.code); }
        }
    }
    @Test public void failedDispatchIsNotRetriedOnAnotherSession() throws Exception {
        Player first=player("video",PlaybackState.STATE_PLAYING),other=player("music",PlaybackState.STATE_PLAYING);
        first.fail=true;
        try { MediaPlaybackControl.execute("pause",Arrays.asList(first,other),"video");fail(); }
        catch(CommandFailure e) { assertEquals("dispatch_failed",e.code); }
        assertEquals(Arrays.asList("pause"),first.sent);assertTrue(other.sent.isEmpty());
    }
}
