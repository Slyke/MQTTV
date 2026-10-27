package net.phoxis.tvmqtt;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.file.Files;
import java.security.cert.CertificateFactory;

public final class MainActivity extends Activity {
    private TextView driver,topic,status,api;
    private Button phone;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable refresh=new Runnable() {
        public void run() { update();handler.postDelayed(this,2000); }
    };
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(20,37,43));
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(40),dp(20),dp(40),dp(20));scroll.addView(layout);
        layout.addView(text(getString(R.string.app_name),26,Color.WHITE));
        LinearLayout summary=new LinearLayout(this);summary.setPadding(0,dp(12),0,dp(18));
        driver=cell(summary,"DRIVER",1);topic=cell(summary,"TV TOPIC",1.7f);
        status=cell(summary,"MQTT",1.2f);api=cell(summary,"API",1.2f);
        layout.addView(summary);
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.VERTICAL);
        // A two-button-wide control area; leave the summary table full width.
        int available=getResources().getConfiguration().screenWidthDp-80;
        layout.addView(actions,new LinearLayout.LayoutParams(available>=840?dp(available/2):-1,-2));
        LinearLayout phoneRow=new LinearLayout(this);
        phone=button(phoneRow,"Open on phone",()->PhoneLink.open(this));phone.setId(R.id.open_on_phone);
        actions.addView(phoneRow);
        LinearLayout settingsRow=new LinearLayout(this);
        Button connection=button(settingsRow,"Connection settings",()->startActivity(new Intent(this,SettingsActivity.class)));
        button(settingsRow,"Advanced options",this::advanced);actions.addView(settingsRow);
        LinearLayout serviceRow=new LinearLayout(this);
        button(serviceRow,"Start",()->{
            try {
                ConfigStore.load(this);
                startForegroundService(new Intent(this,MqttService.class));
                getSharedPreferences("service",0).edit().putString("status","Starting").apply();update();
            } catch(Exception e) { toast("Open Connection settings and save a valid configuration first"); }
        });
        button(serviceRow,"Stop",()->{
            getSharedPreferences("service",0).edit().putBoolean("enabled",false).putString("status","Stopped").apply();
            stopService(new Intent(this,MqttService.class));update();
        });actions.addView(serviceRow);
        layout.addView(text("Press Back to return to your TV app. Remote control continues in the background.",15,Color.LTGRAY));
        setContentView(scroll);
        update();
        (phone.getVisibility()==View.VISIBLE?phone:connection).requestFocus();
    }
    private TextView cell(LinearLayout row,String title,float weight) {
        LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);column.setPadding(dp(10),dp(8),dp(8),dp(8));
        GradientDrawable bg=new GradientDrawable();bg.setColor(Color.rgb(25,46,54));bg.setStroke(dp(1),Color.rgb(63,91,99));column.setBackground(bg);
        column.addView(text(title,12,Color.rgb(150,178,186)));
        TextView value=text("",16,Color.WHITE);value.setMaxLines(2);value.setEllipsize(TextUtils.TruncateAt.END);column.addView(value);
        row.addView(column,new LinearLayout.LayoutParams(0,dp(82),weight));return value;
    }
    private void advanced() {
        String[] items={"Driver setup & permissions","Import JSON configuration","Import broker CA certificate","Allow current-app reporting",
                "On-device control (Accessibility)","Allow playback control & media reporting","Connection details"};
        new AlertDialog.Builder(this).setTitle("Advanced options").setItems(items,(dialog,index)->{
            switch(index) {
                case 0:driverSetup();break;
                case 1:choose(1,"application/json");break;
                case 2:choose(2,"*/*");break;
                case 3:settings(Settings.ACTION_USAGE_ACCESS_SETTINGS);break;
                case 4:settings(Settings.ACTION_ACCESSIBILITY_SETTINGS);break;
                case 5:settings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);break;
                case 6:new AlertDialog.Builder(this).setTitle("Connection details")
                        .setMessage(getSharedPreferences("service",0).getString("status","Stopped")+"\n"+
                                getSharedPreferences("service",0).getString("api_status","API disabled"))
                        .setPositiveButton("Back",null).show();break;
            }
        }).show();
    }
    private void driverSetup() {
        boolean shield="NVIDIA".equalsIgnoreCase(Build.MANUFACTURER);
        try { shield="nvidia-shield".equals(ConfigStore.load(this).tvDriver); } catch(Exception ignored) {}
        new AlertDialog.Builder(this).setTitle(shield?"NVIDIA Shield setup":"Sony BRAVIA setup")
                .setMessage(getString(shield?R.string.driver_setup_shield:R.string.driver_setup_sony)+"\n\n"+getString(R.string.driver_setup_debug))
                .setPositiveButton("Close",null)
                .setNeutralButton("Accessibility settings",(dialog,which)->settings(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                .setNegativeButton("Developer options",(dialog,which)->settings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)).show();
    }
    private TextView text(String value,int size,int color) {
        TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);return view;
    }
    private Button button(LinearLayout parent,String title,Runnable action) {
        Button button=new Button(this);button.setText(title);button.setAllCaps(false);button.setTextSize(18);button.setTextColor(Color.WHITE);
        button.setPadding(dp(12),0,dp(12),0);
        button.setOnFocusChangeListener((view,focused)->paint(button,focused));paint(button,false);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,dp(56),1);params.rightMargin=dp(10);params.bottomMargin=dp(12);
        parent.addView(button,params);button.setOnClickListener(view->action.run());return button;
    }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private void paint(Button button,boolean focused) {
        GradientDrawable bg=new GradientDrawable();bg.setCornerRadius(dp(6));bg.setColor(Color.rgb(33,61,70));
        bg.setStroke(dp(focused?3:1),focused?Color.rgb(72,201,176):Color.rgb(75,103,112));button.setBackground(bg);
    }
    private void choose(int code,String type) {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(type).addCategory(Intent.CATEGORY_OPENABLE),code);
        } catch(ActivityNotFoundException e) { toast("No file picker on this TV. Use the documented ADB config installer."); }
    }
    private void settings(String action) {
        try { startActivity(new Intent(action)); }
        catch(ActivityNotFoundException e) { toast("This TV needs the documented ADB permission command."); }
    }
    protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK || data==null || data.getData()==null) return;
        try {
            byte[] bytes=read(data.getData(),request==1?262144:65536);
            if(request==1) {
                ConfigStore.save(this,bytes);
                getSharedPreferences("service",0).edit().putBoolean("enabled",false).putString("status","Config imported; press Start").apply();
                stopService(new Intent(this,MqttService.class));
                toast("Configuration imported");
            } else {
                CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(bytes));
                String name="broker-ca.pem";
                try { String configured=ConfigStore.load(this).caFile; if(!configured.isEmpty()) name=configured; } catch(Exception ignored) {}
                Files.write(new File(getFilesDir(),name).toPath(),bytes);
                toast("CA certificate imported");
            }
            update();
        } catch(Exception e) { toast("Import failed. Check file format, config fields and file size."); }
    }
    private byte[] read(Uri uri,int max) throws Exception {
        try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            if(in==null) throw new IOException("Cannot open file");
            byte[] block=new byte[4096]; int n;
            while((n=in.read(block))!=-1) {
                if(out.size()+n>max) throw new IOException("File too large");
                out.write(block,0,n);
            }
            return out.toByteArray();
        }
    }
    private void toast(String value) { Toast.makeText(this,value,Toast.LENGTH_LONG).show(); }
    private void update() {
        String connection=getSharedPreferences("service",0).getString("status","Stopped");
        try {
            AppConfig config=ConfigStore.load(this);
            phone.setVisibility(config.apiHttpEnabled || config.apiHttpsEnabled?View.VISIBLE:View.GONE);
            driver.setText("nvidia-shield".equals(config.tvDriver)?"NVIDIA Shield\n"+(config.tvControlMethod.equals("adb")?"Developer fallback":RemoteTextService.ready()?"On-device control":"Enable control"):"Sony BRAVIA");
            topic.setText(config.baseTopic);
            status.setText((connection.startsWith("Connected")?"Connected":connection)+"\n"+(config.tls?"TLS":"Plaintext"));
            String protocols=(config.apiHttpsEnabled?"HTTPS :"+config.apiHttpsPort:"")+
                    (config.apiHttpEnabled?(config.apiHttpsEnabled?" / ":"")+"HTTP :"+config.apiHttpPort:"");
            String state=getSharedPreferences("service",0).getString("api_status","");
            api.setText(protocols.isEmpty()?"Disabled":protocols+"\n"+(state.contains("ready")?"Ready":"Starting / unavailable"));
        } catch(Exception e) {
            phone.setVisibility(View.GONE);
            driver.setText("Not configured");topic.setText("Set in settings");status.setText(connection);api.setText("Not configured");
        }
    }
    protected void onResume() { super.onResume(); handler.post(refresh); }
    protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
}
