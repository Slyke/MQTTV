package net.phoxis.tvmqtt;
import android.content.*;
// Debug APK only. Shell/system callers holding DUMP can start the service without waking the UI.
public final class DebugReceiver extends BroadcastReceiver {
    public void onReceive(Context context,Intent intent) {
        if("net.phoxis.tvmqtt.DEBUG_START".equals(intent.getAction()))
            context.startForegroundService(new Intent(context,MqttService.class));
        else if("net.phoxis.tvmqtt.DEBUG_STOP".equals(intent.getAction())) {
            context.getSharedPreferences("service",0).edit().putBoolean("enabled",false).apply();
            context.stopService(new Intent(context,MqttService.class));
        }
    }
}
