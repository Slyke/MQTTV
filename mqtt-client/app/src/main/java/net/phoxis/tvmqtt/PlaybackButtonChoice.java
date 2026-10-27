package net.phoxis.tvmqtt;
import java.util.*;

/** Prefer current visible controls, then uniquely identified controls hidden by the player overlay. */
final class PlaybackButtonChoice<T> {
    final List<T> visible=new ArrayList<>(),hidden=new ArrayList<>();
    private int oppositeVisible,oppositeHidden;
    void requested(T button,boolean shown) { (shown?visible:hidden).add(button); }
    void opposite(boolean shown) { if(shown)oppositeVisible++;else oppositeHidden++; }
    private boolean useVisible() { return !visible.isEmpty() || oppositeVisible>0; }
    T selected() {
        List<T> choices=useVisible()?visible:hidden;
        return choices.size()==1?choices.get(0):null;
    }
    boolean alreadyInState() {
        return (useVisible()?visible:hidden).isEmpty() && (useVisible()?oppositeVisible:oppositeHidden)==1;
    }
    boolean controlsHidden() { return !useVisible(); }
}
