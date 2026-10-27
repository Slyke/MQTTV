package net.phoxis.tvmqtt;

import android.content.*;
import android.content.pm.PackageInfo;
import android.os.*;
import org.json.*;
import java.time.Instant;

public final class SystemTelemetry {
    private final Context context;
    private final long started=SystemClock.elapsedRealtime();
    private final String bootTime=Instant.ofEpochMilli(System.currentTimeMillis()-SystemClock.elapsedRealtime()).toString();
    public SystemTelemetry(Context context) { this.context=context; }
    public JSONObject snapshot() throws Exception {
        StatFs files=new StatFs(context.getFilesDir().getAbsolutePath());
        long available=files.getAvailableBytes(), total=files.getTotalBytes();
        PackageInfo app=context.getPackageManager().getPackageInfo(context.getPackageName(),0);
        PowerManager power=(PowerManager)context.getSystemService(Context.POWER_SERVICE);
        JSONObject storage=Jsons.object("available_bytes",available,"free_bytes",files.getFreeBytes(),"total_bytes",total,
                "available_mb",Math.round(available/1048576.0),"total_mb",Math.round(total/1048576.0),
                "low_storage",available<Math.max(256L*1024*1024,total/20));
        JSONObject system=Jsons.object("manufacturer",Build.MANUFACTURER,"model",Build.MODEL,
                "android_version",Build.VERSION.RELEASE,"android_sdk",Build.VERSION.SDK_INT,
                "cpu_cores",Runtime.getRuntime().availableProcessors(),"abis",new JSONArray(java.util.Arrays.asList(Build.SUPPORTED_ABIS)),
                "boot_time_estimate",bootTime,"interactive",power!=null && power.isInteractive(),
                "power_save_mode",power!=null && power.isPowerSaveMode(),"device_idle",power!=null && power.isDeviceIdleMode(),
                "awake_seconds",SystemClock.uptimeMillis()/1000);
        JSONObject application=Jsons.object("package",context.getPackageName(),"version",app.versionName,
                "version_code",app.versionCode,"pid",android.os.Process.myPid(),
                "uptime_seconds",(SystemClock.elapsedRealtime()-started)/1000,"cpu_time_ms",android.os.Process.getElapsedCpuTime());
        return Jsons.object("system",system,"storage",storage,"application",application);
    }
}
