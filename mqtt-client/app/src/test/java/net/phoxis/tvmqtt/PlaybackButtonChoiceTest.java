package net.phoxis.tvmqtt;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackButtonChoiceTest {
    @Test public void uniqueHiddenButtonCanControlPlaybackWithoutRevealingTheOverlay() {
        PlaybackButtonChoice<String> choice=new PlaybackButtonChoice<>();
        choice.requested("hidden pause button",false);
        assertEquals("hidden pause button",choice.selected());assertTrue(choice.controlsHidden());assertFalse(choice.alreadyInState());
    }
    @Test public void visibleButtonWinsOverHiddenDuplicates() {
        PlaybackButtonChoice<String> choice=new PlaybackButtonChoice<>();
        choice.requested("old",false);choice.requested("current",true);choice.requested("other old",false);
        assertEquals("current",choice.selected());assertFalse(choice.controlsHidden());
    }
    @Test public void visibleOppositeStatePreventsClickingAnOutdatedHiddenControl() {
        PlaybackButtonChoice<String> choice=new PlaybackButtonChoice<>();
        choice.requested("old play",false);choice.opposite(true);
        assertNull(choice.selected());assertTrue(choice.alreadyInState());
    }
    @Test public void multipleHiddenButtonsAreAmbiguous() {
        PlaybackButtonChoice<String> choice=new PlaybackButtonChoice<>();
        choice.requested("one",false);choice.requested("two",false);
        assertNull(choice.selected());assertFalse(choice.alreadyInState());
    }
    @Test public void ambiguousVisibleControlsNeverFallBackToHiddenControls() {
        PlaybackButtonChoice<String> choice=new PlaybackButtonChoice<>();
        choice.requested("visible one",true);choice.requested("visible two",true);choice.requested("hidden",false);
        assertNull(choice.selected());assertFalse(choice.alreadyInState());
    }
    @Test public void hiddenOppositeLabelMakesExplicitPlayPauseIdempotent() {
        PlaybackButtonChoice<String> choice=new PlaybackButtonChoice<>();choice.opposite(false);
        assertNull(choice.selected());assertTrue(choice.alreadyInState());assertTrue(choice.controlsHidden());
    }
}
