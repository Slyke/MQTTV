package net.phoxis.tvmqtt;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

public final class DeviceName {
    private DeviceName() {}
    public static String read(Context context) {
        String value=null;
        try { value=Settings.Global.getString(context.getContentResolver(),"device_name"); }
        catch(RuntimeException ignored) {}
        if(value==null || value.trim().isEmpty()) value=Build.MODEL;
        return value==null || value.trim().isEmpty()?"Android TV":value.trim();
    }
    public static String clientId(Context context) {
        String suffix=null;
        try { suffix=Settings.Secure.getString(context.getContentResolver(),Settings.Secure.ANDROID_ID); }
        catch(RuntimeException ignored) {}
        return "tv-"+DeviceTopics.slug(read(context))+
                (suffix==null || suffix.isEmpty()?"":"-"+suffix.substring(0,Math.min(8,suffix.length())));
    }
}
