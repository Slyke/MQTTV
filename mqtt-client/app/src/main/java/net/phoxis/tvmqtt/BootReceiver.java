package net.phoxis.tvmqtt;
import android.content.*;

public final class BootReceiver extends BroadcastReceiver {
    public void onReceive(Context context,Intent intent) {
        if(!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        try {
            if(ConfigStore.load(context).startOnBoot && context.getSharedPreferences("service",0).getBoolean("enabled",false))
                context.startForegroundService(new Intent(context,MqttService.class));
        } catch(Exception ignored) {}
    }
}
