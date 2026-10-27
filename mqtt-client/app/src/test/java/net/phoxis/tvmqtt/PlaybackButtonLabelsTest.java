package net.phoxis.tvmqtt;
import org.junit.Test;
import static org.junit.Assert.*;
public class PlaybackButtonLabelsTest {
    @Test public void exactLabelsAcceptCaseAndWhitespaceButNeverTitlesOrPlaybackMenus() {
        assertTrue(PlaybackButtonLabels.matches(PlaybackButtonLabels.labels("play")," Play "));
        for(String label:new String[]{"Play trailer","Playback speed","Play movie title","Select subtitle track"})
            assertFalse(PlaybackButtonLabels.matches(PlaybackButtonLabels.labels("play"),label));
        assertTrue(PlaybackButtonLabels.matches(PlaybackButtonLabels.labels("fast_forward"),"Fast Forward"));
    }
    @Test public void playAndPauseRemainExplicitInsteadOfClickingTheOppositeControl() {
        assertFalse(PlaybackButtonLabels.matches(PlaybackButtonLabels.labels("play"),"Pause"));
        assertEquals("pause",PlaybackButtonLabels.opposite("play"));
        assertEquals("play",PlaybackButtonLabels.opposite("pause"));
        assertEquals("",PlaybackButtonLabels.opposite("next"));
    }
    @Test public void localizedLeanbackNamesCanBeAddedWithoutAppSpecificIds() {
        java.util.Set<String> labels=PlaybackButtonLabels.labels("play");
        labels.add(PlaybackButtonLabels.normalize("Reproducir"));
        assertTrue(PlaybackButtonLabels.matches(labels,"Reproducir"));
        assertEquals("lb_playback_controls_skip_next",PlaybackButtonLabels.resource("next"));
        assertTrue(PlaybackButtonLabels.labels("arbitrary").isEmpty());
    }
}
