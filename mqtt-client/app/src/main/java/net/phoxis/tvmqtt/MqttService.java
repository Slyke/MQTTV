package net.phoxis.tvmqtt;

import android.annotation.SuppressLint;
import android.app.*;
import android.content.*;
import android.net.wifi.WifiManager;
import android.os.*;
import org.json.*;
import java.util.Iterator;

public final class MqttService extends Service {
    private static final int NOTICE=1;
    private static final String CHANNEL="mqtt_connection";
    private final Handler main=new Handler(Looper.getMainLooper());
    private MqttEngine engine;
    private ShieldDriver shieldControl;
    private volatile ApiServerGroup api;
    private volatile boolean destroying;
    public static final String RELOAD="net.phoxis.tvmqtt.RELOAD";
    // Serialize shutdown/start across service instances, including rapid Stop/Start.
    private static final java.util.concurrent.ExecutorService lifecycle=java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread=new Thread(r,"tv-runtime");thread.setDaemon(true);return thread;
    });
    private volatile long revision;
    private PowerManager.WakeLock cpu;
    private WifiManager.WifiLock wifi;
    private boolean started;
    public IBinder onBind(Intent intent) { return null; }
    public void onCreate() {
        super.onCreate();
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel=new NotificationChannel(CHANNEL,"TV remote connection",NotificationManager.IMPORTANCE_MIN);
        channel.setSound(null,null);
        channel.enableVibration(false);
        manager.createNotificationChannel(channel);
        startForeground(NOTICE,notification("Starting"));
    }
    public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent!=null && "STOP".equals(intent.getAction())) {
            getSharedPreferences("service",0).edit().putBoolean("enabled",false).apply();
            stopSelf(); return START_NOT_STICKY;
        }
        if(started && (intent==null || !RELOAD.equals(intent.getAction()))) return START_STICKY;
        started=true;
        long token=++revision;
        update(intent!=null && RELOAD.equals(intent.getAction())?"Applying settings":"Starting");
        lifecycle.execute(() -> {
            try {
                stopRuntime();
                if(destroying || token!=revision) return;
                startRuntime(token);
            } catch(Exception e) {
                main.post(() -> {
                    if(!destroying && token==revision) {
                        update("Could not apply settings; check configuration and connection status");
                        stopSelf();
                    }
                });
            }
        });
        return START_STICKY;
    }
    private void startRuntime(long token) throws Exception {
        ServerIdentity.applyPending(getFilesDir());
        AppConfig config=ConfigStore.load(this);
        getSharedPreferences("service",0).edit().putBoolean("enabled",true).apply();
        acquireLocks(config);
        boolean shield="nvidia-shield".equals(config.tvDriver);
        SonyApi sony=shield?null:new SonyApi(config);
        ShieldDriver shieldDriver=shield?new ShieldDriver(this,config):null;
        shieldControl=shieldDriver;
        DeviceTelemetry telemetry=new DeviceTelemetry(this);
        TvCommands.Snapshot source=() -> {
            JSONObject state=telemetry.snapshot();
            try {
                JSONObject tv=shield?shieldDriver.snapshot():sony.snapshot();
                tv.put("driver",config.tvDriver);
                Iterator<String> keys=tv.keys();
                while(keys.hasNext()) { String key=keys.next(); state.put(key,tv.get(key)); }
            } catch(Exception e) {
                state.put("reachable",false);
                state.put("power","unknown");
                state.put("state","unavailable");
                state.put("tv_error",e instanceof CommandFailure?((CommandFailure)e).code:"tv_unreachable");
            }
            state.put("mqtt",Jsons.object("base_topic",config.baseTopic));
            state.put("text_input",Jsons.object("android_ready",RemoteTextService.ready(),"mode",shield?(config.tvControlMethod.equals("adb")?"developer_adb":"android_accessibility"):"focused_field","adb_enabled",config.textAdbEnabled));
            ApiServerGroup currentApi=api;
            state.put("api",currentApi==null?Jsons.object("ready",false,"http_enabled",false,"https_enabled",false):currentApi.metadata());
            return state;
        };
        engine=new MqttEngine(config,getFilesDir(),shield?command -> shieldDriver.execute(command,source):new TvCommands(sony,source,new AndroidTextInput(config,getFilesDir(),getApplicationInfo().sourceDir)),source,new MqttEngine.Listener() {
            public void status(String message) { main.post(() -> { if(!destroying && token==revision) update(message); }); }
            public void fatal(String message) { main.post(() -> { if(!destroying && token==revision) { update(message); stopSelf(); } }); }
        });
        engine.start();
        getSharedPreferences("service",0).edit().putString("api_status","API listeners disabled").apply();
        if(config.apiHttpEnabled || config.apiHttpsEnabled) {
            ApiServerGroup next=new ApiServerGroup(config,engine,() -> ServerIdentity.load(getFilesDir()),name -> {
                try(java.io.InputStream input=getAssets().open("remote/"+name);java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream()) {
                    byte[] block=new byte[4096];int size;
                    while((size=input.read(block))!=-1) output.write(block,0,size);
                    return output.toByteArray();
                }
            });
            api=next;
            try {
                next.start();
                if(destroying || token!=revision) next.stop();
                else getSharedPreferences("service",0).edit().putString("api_status","API listeners ready").apply();
            } catch(Exception e) {
                android.util.Log.e("TvApi","API startup failed",e);
                getSharedPreferences("service",0).edit().putString("api_status","API unavailable; check bind, ports or keystore").apply();
            }
        }
    }
    private void stopRuntime() throws Exception {
        try {
            ApiServerGroup oldApi=api;api=null;
            if(oldApi!=null) oldApi.stop();
            if(engine!=null) {
                engine.stop();
                if(!engine.awaitStopped(30000)) throw new java.io.IOException("Previous command processor is still stopping");
                engine=null;
            }
        } finally {
            if(shieldControl!=null) { shieldControl.close();shieldControl=null; }
            if(cpu!=null && cpu.isHeld()) cpu.release();
            if(wifi!=null && wifi.isHeld()) wifi.release();
            cpu=null;wifi=null;
        }
    }
    @SuppressLint("WakelockTimeout")
    private void acquireLocks(AppConfig config) {
        if(config.keepCpuAwake) {
            PowerManager manager=(PowerManager)getSystemService(POWER_SERVICE);
            cpu=manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"tvmqtt:connection");
            cpu.setReferenceCounted(false); cpu.acquire();
        }
        if(config.keepWifiAwake) {
            WifiManager manager=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
            if(manager!=null) {
                wifi=manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"tvmqtt:connection");
                wifi.setReferenceCounted(false); wifi.acquire();
            }
        }
    }
    private Notification notification(String status) {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,MqttService.class).setAction("STOP"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_remote_status).setContentTitle(getString(R.string.app_name))
                .setContentText(status).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null,"Stop",stop).build()).build();
    }
    private void update(String status) {
        getSharedPreferences("service",0).edit().putString("status",status).apply();
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTICE,notification(status));
        sendBroadcast(new Intent(getPackageName()+".STATUS").setPackage(getPackageName()));
    }
    public void onDestroy() {
        destroying=true;++revision;
        lifecycle.execute(() -> {
            try { stopRuntime(); }
            catch(Exception e) { android.util.Log.e("TvApi","Runtime shutdown did not finish",e); }
        });
        stopForeground(true);
        super.onDestroy();
    }
}
