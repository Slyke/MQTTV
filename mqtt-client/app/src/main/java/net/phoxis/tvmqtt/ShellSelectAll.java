package net.phoxis.tvmqtt;

import android.os.SystemClock;
import android.view.*;
import java.lang.reflect.Method;

/** Standard Ctrl+A shortcut executed by the already-authenticated local ADB shell. */
public final class ShellSelectAll {
    public static void main(String[] args) throws Exception {
        if(args.length!=0) throw new IllegalArgumentException("No arguments accepted");
        Class<?> type=Class.forName("android.hardware.input.InputManager");
        Object manager=type.getMethod("getInstance").invoke(null);
        Method inject=type.getMethod("injectInputEvent",InputEvent.class,int.class);
        long when=SystemClock.uptimeMillis();
        int ctrl=KeyEvent.META_CTRL_ON|KeyEvent.META_CTRL_LEFT_ON;
        try {
            key(manager,inject,when,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_CTRL_LEFT,ctrl);
            key(manager,inject,when,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_A,ctrl);
            key(manager,inject,when,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_A,ctrl);
        } finally { key(manager,inject,when,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_CTRL_LEFT,0); }
    }
    private static void key(Object manager,Method inject,long down,int action,int code,int modifiers) throws Exception {
        KeyEvent event=new KeyEvent(down,SystemClock.uptimeMillis(),action,code,0,modifiers,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD);
        if(!Boolean.TRUE.equals(inject.invoke(manager,event,2))) throw new IllegalStateException("Shortcut was rejected");
    }
}
