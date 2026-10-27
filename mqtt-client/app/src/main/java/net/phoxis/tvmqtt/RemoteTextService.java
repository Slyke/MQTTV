package net.phoxis.tvmqtt;

import android.accessibilityservice.AccessibilityService;
import android.os.*;
import android.view.accessibility.*;
import android.view.View;
import org.json.JSONObject;
import java.util.concurrent.*;

public final class RemoteTextService extends AccessibilityService {
    private static volatile RemoteTextService connected;
    private static volatile String currentPackage="";
    private final Handler main=new Handler(Looper.getMainLooper());
    protected void onServiceConnected() { connected=this; }
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Only the foreground package is telemetry. Never record event text.
        if(event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.getPackageName()!=null)
            currentPackage=event.getPackageName().toString();
    }
    public void onInterrupt() {}
    public void onDestroy() { if(connected==this) connected=null; super.onDestroy(); }
    public static boolean ready() { return connected!=null; }
    public static String currentPackage() { return ready()?currentPackage:""; }
    public static JSONObject control(String action) throws Exception {
        RemoteTextService service=connected;
        if(service==null) throw new CommandFailure("control_access_unavailable",
                "Enable TV remote control in Android Accessibility settings. Debugging is not required.");
        FutureTask<JSONObject> task=new FutureTask<>(() -> service.controlFocused(action));
        if(Looper.myLooper()==Looper.getMainLooper()) return service.controlFocused(action);
        if(!service.main.post(task)) throw new CommandFailure("control_access_unavailable","TV control is restarting.");
        try { return task.get(8,TimeUnit.SECONDS); }
        catch(TimeoutException e) { task.cancel(false);throw new CommandFailure("control_timeout","Control timed out and may have executed; retry the same command ID."); }
        catch(ExecutionException e) { if(e.getCause() instanceof Exception) throw (Exception)e.getCause();throw e; }
        catch(InterruptedException e) { task.cancel(false);Thread.currentThread().interrupt();throw e; }
    }
    private JSONObject controlFocused(String action) throws Exception {
        if(connected!=this) throw new CommandFailure("control_access_unavailable","TV control is restarting.");
        boolean accepted=false;
        switch(action) {
            case "home": accepted=performGlobalAction(GLOBAL_ACTION_HOME);break;
            case "back": accepted=performGlobalAction(GLOBAL_ACTION_BACK);break;
            case "power_off":
                if(Build.VERSION.SDK_INT<28) throw new CommandFailure("unsupported_command","This Android version does not support Accessibility standby.");
                accepted=performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);break;
            case "up": case "down": case "left": case "right": case "ok":
                AccessibilityNodeInfo node=findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if(node==null) throw new CommandFailure("control_not_focused","The current app does not expose a focused control.");
                try {
                    if(action.equals("ok")) accepted=node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    else {
                        int direction=action.equals("up")?View.FOCUS_UP:action.equals("down")?View.FOCUS_DOWN:
                                action.equals("left")?View.FOCUS_LEFT:View.FOCUS_RIGHT;
                        AccessibilityNodeInfo next=node.focusSearch(direction);
                        if(next!=null) try {
                            accepted=next.isFocused() || next.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                        }
                        finally { next.recycle(); }
                        else if(node.isClickable() || node.isEditable()) accepted=true;
                    }
                } finally { node.recycle(); }
                break;
            default: throw new CommandFailure("unsupported_command","This key needs the optional developer fallback on this device.");
        }
        if(!accepted) throw new CommandFailure("control_not_supported","The current app rejected this Accessibility control. Developer mode is available as a fallback.");
        return Jsons.object("accepted",true,"method","android_accessibility","key",action);
    }

    public static JSONObject playback(String action) throws Exception {
        RemoteTextService service=connected;
        if(service==null) throw new CommandFailure("control_access_unavailable",
                "Enable TV remote control in Android Accessibility settings.");
        if(Looper.myLooper()==Looper.getMainLooper()) return service.playbackButton(action);
        FutureTask<JSONObject> task=new FutureTask<>(() -> service.playbackButton(action));
        if(!service.main.post(task)) throw new CommandFailure("control_access_unavailable","TV control is restarting.");
        try { return task.get(8,TimeUnit.SECONDS); }
        catch(TimeoutException e) { task.cancel(false);throw new CommandFailure("control_timeout","Playback control timed out and may have executed; retry the same ID."); }
        catch(ExecutionException e) { if(e.getCause() instanceof Exception) throw (Exception)e.getCause();throw e; }
        catch(InterruptedException e) { task.cancel(false);Thread.currentThread().interrupt();throw e; }
    }
    private java.util.Set<String> playbackLabels(String action,String pkg) {
        java.util.Set<String> labels=PlaybackButtonLabels.labels(action);
        // Android TV Leanback supplies localized accessibility labels inside the foreground app.
        try {
            android.content.res.Resources resources=getPackageManager().getResourcesForApplication(pkg);
            int id=resources.getIdentifier(PlaybackButtonLabels.resource(action),"string",pkg);
            if(id!=0) labels.add(PlaybackButtonLabels.normalize(resources.getText(id)));
        } catch(android.content.pm.PackageManager.NameNotFoundException | android.content.res.Resources.NotFoundException ignored) {}
        return labels;
    }
    private boolean playbackClickable(AccessibilityNodeInfo node) {
        return node.isEnabled() && node.isClickable() && (node.getActions()&AccessibilityNodeInfo.ACTION_CLICK)!=0;
    }
    private JSONObject playbackButton(String action) throws Exception {
        if(!MediaPlaybackControl.supported(action)) throw new CommandFailure("invalid_value","Unknown playback command");
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null) throw new CommandFailure("playback_control_unavailable","The current app does not expose playback controls.");
        String pkg=root.getPackageName()==null?"":root.getPackageName().toString();
        java.util.Set<String> labels=playbackLabels(action,pkg);
        java.util.Set<String> opposite=playbackLabels(PlaybackButtonLabels.opposite(action),pkg);
        java.util.ArrayDeque<AccessibilityNodeInfo> pending=new java.util.ArrayDeque<>();
        PlaybackButtonChoice<AccessibilityNodeInfo> choice=new PlaybackButtonChoice<>();
        int visited=0;
        pending.add(root);
        try {
            while(!pending.isEmpty()) {
                AccessibilityNodeInfo node=pending.removeFirst();
                try {
                    if(++visited>600) throw new CommandFailure("playback_control_unavailable","The current screen has too many controls to select one safely.");
                    if(!pkg.contentEquals(node.getPackageName()==null?"":node.getPackageName())) continue;
                    if(playbackClickable(node)) {
                        boolean shown=node.isVisibleToUser();
                        boolean match=PlaybackButtonLabels.matches(labels,node.getContentDescription())
                                || PlaybackButtonLabels.matches(labels,node.getText());
                        if(match) choice.requested(AccessibilityNodeInfo.obtain(node),shown);
                        if(PlaybackButtonLabels.matches(opposite,node.getContentDescription())
                                || PlaybackButtonLabels.matches(opposite,node.getText())) choice.opposite(shown);
                    }
                    for(int i=0;i<node.getChildCount();i++) {
                        AccessibilityNodeInfo child=node.getChild(i);
                        if(child!=null) pending.add(child);
                    }
                } finally { node.recycle(); }
            }
            if(choice.alreadyInState() && !PlaybackButtonLabels.opposite(action).isEmpty())
                return Jsons.object("accepted",true,"method","android_accessibility_playback","key",action,
                        "package",pkg,"already_in_state",true,"controls_hidden",choice.controlsHidden());
            AccessibilityNodeInfo selected=choice.selected();
            if(selected==null) throw new CommandFailure("playback_control_unavailable",
                    "The player does not expose one unambiguous "+action+" control.");
            if(!selected.refresh() || !playbackClickable(selected)
                    || !(PlaybackButtonLabels.matches(labels,selected.getContentDescription())
                        || PlaybackButtonLabels.matches(labels,selected.getText())))
                throw new CommandFailure("playback_control_unavailable","The playback controls changed; try again.");
            // A player may fade its controls out while keeping their standard click actions available.
            // Invoke the named control directly; do not tap coordinates, move focus, or synthesize keys.
            boolean hidden=!selected.isVisibleToUser();
            if(!selected.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                throw new CommandFailure("playback_control_unavailable","The player rejected the playback button.");
            return Jsons.object("accepted",true,"method","android_accessibility_playback","key",action,
                    "package",pkg,"already_in_state",false,"controls_hidden",hidden);
        } finally {
            for(AccessibilityNodeInfo node:choice.visible) node.recycle();
            for(AccessibilityNodeInfo node:choice.hidden) node.recycle();
            while(!pending.isEmpty()) pending.removeFirst().recycle();
        }
    }

    public static JSONObject setText(String text) throws Exception { return perform(text,0,0); }
    public static JSONObject backspace() throws Exception { return backspace(1); }
    public static JSONObject backspace(int count) throws Exception { return perform(null,1,count); }
    public static JSONObject selectAll() throws Exception { return perform(null,2,0); }
    private static JSONObject perform(String text,int operation,int count) throws Exception {
        RemoteTextService service=connected;
        if(service==null) throw new CommandFailure("text_access_unavailable",
                "TV remote text access is not enabled. Enable it with the setup screen or installer.");
        if(Looper.myLooper()==Looper.getMainLooper()) return service.writeFocused(text,operation,count);
        FutureTask<JSONObject> task=new FutureTask<>(() -> service.writeFocused(text,operation,count));
        if(!service.main.post(task)) throw new CommandFailure("text_access_unavailable","TV text service is restarting.");
        try { return task.get(8,TimeUnit.SECONDS); }
        catch(TimeoutException error) {
            task.cancel(false);
            throw new CommandFailure("text_timeout","Text entry timed out and may have executed; retry the same command ID.");
        } catch(ExecutionException error) {
            Throwable cause=error.getCause();
            if(cause instanceof Exception) throw (Exception)cause;
            throw error;
        } catch(InterruptedException error) {
            task.cancel(false);Thread.currentThread().interrupt();throw error;
        }
    }
    private JSONObject writeFocused(String text,int operation,int count) throws Exception {
        if(connected!=this) throw new CommandFailure("text_access_unavailable","TV text service is restarting.");
        AccessibilityNodeInfo node=findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if(node==null) throw new CommandFailure("text_not_focused",
                "Focus the app's search text box on the TV before sending text.");
        try {
            if(!node.isFocused() || !node.isEditable() || !node.isEnabled() || !node.isVisibleToUser())
                throw new CommandFailure("text_not_focused",
                        "Focus the app's search text box on the TV before sending text.");
            if(operation==2) {
                CharSequence value=node.getText();
                Bundle selection=new Bundle();
                selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,0);
                selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,value==null?0:value.length());
                if(!node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,selection))
                    throw new CommandFailure("text_not_supported","This field does not support Select All.");
                return Jsons.object("accepted",true,"method","android_focused_selection");
            }
            boolean supported=false;
            for(AccessibilityNodeInfo.AccessibilityAction action:node.getActionList())
                if(action.getId()==AccessibilityNodeInfo.ACTION_SET_TEXT) supported=true;
            if(!supported) throw new CommandFailure("text_not_supported","This text field does not expose Android text entry.");
            int cursor=-1;
            if(operation==1) {
                // Password nodes may expose redacted text. Let the real keyboard handle those.
                if(node.isPassword()) throw new CommandFailure("text_not_supported","Password deletion needs the keyboard input path.");
                CharSequence existing=node.getText();
                TextDeletion edit=TextDeletion.backspace(existing==null?"":existing.toString(),
                        node.getTextSelectionStart(),node.getTextSelectionEnd(),count);
                text=edit.text;cursor=edit.cursor;
            }
            Bundle arguments=new Bundle();
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);
            if(!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,arguments))
                throw new CommandFailure("text_not_supported","The focused text field rejected Android text entry.");
            if(operation==1) {
                Bundle selection=new Bundle();
                selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,cursor);
                selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,cursor);
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,selection);
            }
            // Do not return the text itself or read existing field contents into telemetry.
            return Jsons.object("accepted",true,"method","android_focused_text");
        } finally { node.recycle(); }
    }
}
