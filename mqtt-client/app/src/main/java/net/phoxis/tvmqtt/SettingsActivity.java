package net.phoxis.tvmqtt;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Remote-friendly editor; advanced and unknown JSON settings survive a save. */
public final class SettingsActivity extends Activity {
    private final Map<String,EditText> fields=new LinkedHashMap<>();
    private final Map<String,CheckBox> checks=new LinkedHashMap<>();
    private final List<LinearLayout> sections=new ArrayList<>();
    private final List<Button> tabs=new ArrayList<>();
    private JSONObject original;
    private Spinner driver;
    private LinearLayout sonyFields,shieldFields,nativeFields,adbFields;
    private Spinner shieldMethod,shieldVolume;
    private CheckBox developerEnabled;
    private Button controlAccess;
    private ScrollView scroll;
    private TextView error;
    private Bundle restored;
    private int selected;
    private Button saveButton;
    private LinearLayout mqttAdvanced;
    private String pendingIdentity;
    private TextView identityStatus;
    private boolean identityBusy;
    private final java.util.concurrent.ExecutorService keyWork=java.util.concurrent.Executors.newSingleThreadExecutor();
    private final int ink=Color.rgb(235,243,245),muted=Color.rgb(175,195,201),accent=Color.rgb(72,201,176);

    public void onCreate(Bundle saved) {
        super.onCreate(saved);restored=saved;
        pendingIdentity=saved==null?null:saved.getString("pending_identity");
        try {
            JSONObject existing=null;
            if(ConfigStore.exists(this)) existing=ConfigStore.read(this);
            original=ConfigEditor.initial(existing,DeviceName.clientId(this),
                    "NVIDIA".equalsIgnoreCase(Build.MANUFACTURER)?"nvidia-shield":"sony-bravia");
        } catch(Exception e) {
            new AlertDialog.Builder(this).setTitle("Configuration could not be opened")
                    .setMessage("The saved file is unreadable. Import a valid JSON configuration from the main screen.")
                    .setPositiveButton("Back",(dialog,which)->finish()).setOnCancelListener(dialog->finish()).show();
            return;
        }
        LinearLayout root=column();root.setPadding(dp(48),dp(20),dp(48),dp(16));
        root.setBackgroundColor(Color.rgb(20,37,43));
        root.addView(label("Connection settings",26,ink));
        root.addView(label("Device: "+DeviceName.read(this)+"  |  Changes apply when you save.",14,muted));
        LinearLayout navigation=new LinearLayout(this);
        for(String title:new String[]{"MQTT","API","Driver"}) {
            final int index=tabs.size();
            Button button=button(title,()->select(index));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(50),1);lp.setMargins(0,dp(10),dp(8),dp(8));
            navigation.addView(button,lp);tabs.add(button);
        }
        root.addView(navigation);
        scroll=new ScrollView(this);scroll.setFillViewport(false);
        LinearLayout body=column();body.setPadding(0,0,0,dp(8));
        for(int i=0;i<3;i++) { LinearLayout section=column();sections.add(section);body.addView(section); }
        scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        mqtt(sections.get(0));api(sections.get(1));control(sections.get(2));
        error=label("",15,Color.rgb(255,170,155));error.setVisibility(View.GONE);root.addView(error);
        LinearLayout actions=new LinearLayout(this);actions.setPadding(0,dp(12),0,0);
        Button save=button("Save & apply",this::save);saveButton=save;save.setId(R.id.settings_save);
        Button cancel=button("Cancel",this::finish);cancel.setId(R.id.settings_cancel);
        actions.addView(save,new LinearLayout.LayoutParams(dp(230),dp(52)));
        LinearLayout.LayoutParams cancelParams=new LinearLayout.LayoutParams(dp(180),dp(52));cancelParams.leftMargin=dp(16);
        actions.addView(cancel,cancelParams);root.addView(actions);
        setContentView(root);
        select(saved==null?getIntent().getIntExtra("section",0):saved.getInt("section",0));
        tabs.get(selected).requestFocus();
    }
    private void mqtt(LinearLayout section) {
        section.addView(label("Broker connection",20,ink));
        field(section,"Broker URI","mqtt.uri","","mqtts://broker:8883 or mqtt://broker:1883",false,false);
        field(section,"Client ID","mqtt.clientId",DeviceName.clientId(this),"Unique for this TV",false,false);
        field(section,"Username","mqtt.username","","Optional",false,false);
        EditText password=field(section,"Password","mqtt.password","","Optional",true,false);
        reveal(section,password);credentialButtons(section,password,"MQTT password",false);
        field(section,"Base topic (optional)","mqtt.baseTopic","","tvs/"+DeviceTopics.slug(DeviceName.read(this))+"/",false,false);
        section.addView(label("Leave blank to use tvs/"+DeviceTopics.slug(DeviceName.read(this))+"/. A trailing / is optional.",14,muted));
        check(section,"Skip TLS certificate validation","mqtt.tls.insecureSkipVerify",true);
        mqttAdvanced=column();mqttAdvanced.setVisibility(View.GONE);
        Button advanced=button("Advanced MQTT options",()->mqttAdvanced.setVisibility(mqttAdvanced.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));
        LinearLayout.LayoutParams advancedParams=new LinearLayout.LayoutParams(-1,dp(50));advancedParams.topMargin=dp(8);
        section.addView(advanced,advancedParams);section.addView(mqttAdvanced);
        check(mqttAdvanced,"Trust system certificate authorities","mqtt.tls.trustSystemCAs",true);
        field(mqttAdvanced,"CA certificate file","mqtt.tls.caFile","","Optional; import the certificate from the main screen",false,false);
        field(mqttAdvanced,"Keepalive (seconds)","mqtt.keepAliveSeconds","30","10-600",false,true);
    }
    private void api(LinearLayout section) {
        section.addView(label("REST and WebSocket",20,ink));
        EditText password=field(section,"API passkey","api.passkey","","Required when HTTP or HTTPS is enabled",true,false);
        reveal(section,password);credentialButtons(section,password,"API passkey",true);
        field(section,"Listen address","api.bindAddress","0.0.0.0","0.0.0.0 (recommended; DHCP safe)",false,false);
        section.addView(label("0.0.0.0 follows DHCP address changes. A specific IP may stop working after a change.",14,muted));
        check(section,"Enable HTTP / WebSocket","api.http.enabled",false);
        field(section,"HTTP port","api.http.port","8788","1-65535",false,true);
        check(section,"Enable HTTPS / secure WebSocket","api.https.enabled",false);
        field(section,"HTTPS port","api.https.port","8789","1-65535",false,true);
        section.addView(label("Clients send the passkey in X-API-Key.",14,muted));
        httpsIdentity(section);
    }
    private void control(LinearLayout section) {
        section.addView(label("TV control driver",20,ink));
        driver=new Spinner(this);driver.setId(R.id.setting_tv_driver);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,new String[]{"Sony BRAVIA","NVIDIA Shield"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);driver.setAdapter(adapter);
        section.addView(driver,new LinearLayout.LayoutParams(-1,dp(56)));
        int initial="nvidia-shield".equals(value("tv.driver","sony-bravia"))?1:0;
        driver.setSelection(restored==null?initial:restored.getInt("driver",initial));
        sonyFields=column();section.addView(sonyFields);
        field(sonyFields,"Sony API URL","tv.baseUrl","http://127.0.0.1","http://127.0.0.1",false,false);
        EditText password=field(sonyFields,"Sony pre-shared key","tv.preSharedKey",value("tv.psk",""),"Required for Sony BRAVIA",true,false);
        reveal(sonyFields,password);credentialButtons(sonyFields,password,"Sony pre-shared key",false);
        sonyFields.addView(label("Set the matching key in the TV IP-control settings.",14,muted));
        developerEnabled=check(sonyFields,"Developer-mode text fallback (optional)","tv.adb.enabled",false);
        shieldFields=column();section.addView(shieldFields);
        shieldFields.addView(label("Control method",15,muted));
        shieldMethod=new Spinner(this);shieldMethod.setId(R.id.shield_control_method);
        ArrayAdapter<String> methods=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,
                new String[]{"On-device control (recommended)","Developer mode (ADB fallback)"});
        methods.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);shieldMethod.setAdapter(methods);
        int method="adb".equals(value("tv.controlMethod","native"))?1:0;
        shieldMethod.setSelection(restored==null?method:restored.getInt("shield_method",method));
        shieldFields.addView(shieldMethod,new LinearLayout.LayoutParams(-1,dp(56)));
        shieldFields.addView(label("Volume control",15,muted));
        shieldVolume=new Spinner(this);shieldVolume.setId(R.id.shield_volume_control);
        ArrayAdapter<String> volumeModes=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,
                new String[]{"TV / receiver (system remote buttons)","Shield PCM / device audio"});
        volumeModes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);shieldVolume.setAdapter(volumeModes);
        int volumeMode="device".equals(value("tv.volumeControl","system"))?1:0;
        shieldVolume.setSelection(restored==null?volumeMode:restored.getInt("shield_volume",volumeMode));
        shieldFields.addView(shieldVolume,new LinearLayout.LayoutParams(-1,dp(56)));
        shieldFields.addView(label("System volume depends on the connected TV/receiver supporting the Shield\'s controls. IR on the physical remote does not provide app volume control. External volume support is unknown until tested with your TV/receiver. Relative controls remain available. Device mode affects local PCM audio, not passthrough.",14,muted));
        nativeFields=column();shieldFields.addView(nativeFields);
        nativeFields.addView(label("Enable TV remote control in Android Accessibility settings. Developer options and debugging can stay off.",16,muted));
        controlAccess=button("Enable on-device control",()->startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)));controlAccess.setId(R.id.shield_control_access);
        nativeFields.addView(controlAccess,new LinearLayout.LayoutParams(-1,dp(54)));
        nativeFields.addView(label("Uses Android focus, text, audio and power APIs. Apps that do not expose Accessibility controls may need the optional developer fallback.",14,muted));
        Button launchAccess=button("Allow background app launches (optional)",()->{
            try { startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,android.net.Uri.parse("package:"+getPackageName()))); }
            catch(ActivityNotFoundException e) { Toast.makeText(this,"Allow Display over other apps in Android special app access.",Toast.LENGTH_LONG).show(); }
        });
        launchAccess.setId(R.id.shield_launch_access);nativeFields.addView(launchAccess,new LinearLayout.LayoutParams(-1,dp(54)));
        nativeFields.addView(label("Needed for app shortcut buttons on Android 10+. Normal remote navigation works without this permission. No overlay is drawn.",14,muted));
        adbFields=column();section.addView(adbFields);
        adbFields.addView(label("Developer fallback requires network debugging and an already-authorized key.",14,muted));
        field(adbFields,"Local ADB port","tv.adb.port","5555","Network debugging port",false,true);
        field(adbFields,"Authorized ADB key file","tv.adb.keyFile",initial==1?"adbkey.pem":"text-adbkey.pem","Existing app-private key file",false,false);
        AdapterView.OnItemSelectedListener changed=new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id) { updateControlUi(); }
            public void onNothingSelected(AdapterView<?> parent) {}
        };
        driver.setOnItemSelectedListener(changed);shieldMethod.setOnItemSelectedListener(changed);
        developerEnabled.setOnCheckedChangeListener((view,checked)->updateControlUi());
        checks.get("api.http.enabled").setOnCheckedChangeListener((view,checked)->updateControlUi());
        checks.get("api.https.enabled").setOnCheckedChangeListener((view,checked)->updateControlUi());
        updateControlUi();
    }
    private void updateControlUi() {
        if(shieldMethod==null) return;
        boolean shield=driver.getSelectedItemPosition()==1,developer=shieldMethod.getSelectedItemPosition()==1;
        sonyFields.setVisibility(shield?View.GONE:View.VISIBLE);
        shieldFields.setVisibility(shield?View.VISIBLE:View.GONE);
        nativeFields.setVisibility(developer?View.GONE:View.VISIBLE);
        adbFields.setVisibility(shield?developer?View.VISIBLE:View.GONE:developerEnabled.isChecked()?View.VISIBLE:View.GONE);

    }
    private EditText field(LinearLayout parent,String title,String path,String fallback,String hint,boolean secret,boolean numeric) {
        TextView label=label(title,15,muted);label.setPadding(0,dp(10),0,0);parent.addView(label);
        EditText input=new EditText(this);
        int id=getResources().getIdentifier("setting_"+path.replace('.','_').toLowerCase(Locale.ROOT),"id",getPackageName());
        input.setId(id);label.setLabelFor(id);
        input.setSingleLine(true);input.setTextSize(18);input.setTextColor(ink);input.setHintTextColor(Color.rgb(125,149,156));
        input.setInputType(numeric?InputType.TYPE_CLASS_NUMBER:
                InputType.TYPE_CLASS_TEXT|(secret?InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        input.setImeOptions(EditorInfo.IME_ACTION_DONE|EditorInfo.IME_FLAG_NO_EXTRACT_UI|EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        if(secret) input.setTransformationMethod(PasswordTransformationMethod.getInstance());
        input.setText(restored!=null && restored.containsKey(path)?restored.getString(path):value(path,fallback));
        input.setHint(hint);input.setSelectAllOnFocus(true);
        input.setBackgroundTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_focused},new int[]{}},
                new int[]{accent,Color.rgb(95,124,133)}));
        parent.addView(input,new LinearLayout.LayoutParams(-1,dp(56)));fields.put(path,input);return input;
    }
    private CheckBox check(LinearLayout parent,String title,String path,boolean fallback) {
        CheckBox input=new CheckBox(this);input.setText(title);input.setTextSize(17);input.setTextColor(ink);
        input.setId(getResources().getIdentifier("setting_"+path.replace('.','_').toLowerCase(Locale.ROOT),"id",getPackageName()));
        input.setChecked(restored!=null && restored.containsKey(path)?restored.getBoolean(path):Boolean.parseBoolean(value(path,String.valueOf(fallback))));
        parent.addView(input,new LinearLayout.LayoutParams(-1,dp(54)));checks.put(path,input);return input;
    }
    private void reveal(LinearLayout parent,EditText password) {
        CheckBox show=new CheckBox(this);show.setText("Show password");show.setTextColor(muted);show.setTextSize(14);
        show.setOnCheckedChangeListener((view,checked)->{
            int cursor=password.getSelectionStart();
            password.setTransformationMethod(checked?null:PasswordTransformationMethod.getInstance());
            password.setSelection(Math.max(0,cursor));
        });
        parent.addView(show,new LinearLayout.LayoutParams(-1,dp(42)));
    }
    private String value(String path,String fallback) {
        try {
            String[] keys=path.split("\\.");JSONObject current=original;
            for(int i=0;i<keys.length-1;i++) current=current.getJSONObject(keys[i]);
            return current.isNull(keys[keys.length-1])?fallback:current.get(keys[keys.length-1]).toString();
        } catch(JSONException e) { return fallback; }
    }
    private void select(int index) {
        selected=Math.max(0,Math.min(2,index));
        for(int i=0;i<3;i++) {
            sections.get(i).setVisibility(i==selected?View.VISIBLE:View.GONE);
            tabs.get(i).setText(new String[]{"MQTT","API","Driver"}[i]+(i==selected?"  \u2022":""));
        }
        scroll.scrollTo(0,0);
    }
    private void save() {
        try {
            JSONObject edits=new JSONObject();
            for(Map.Entry<String,EditText> item:fields.entrySet()) {
                String path=item.getKey(),text=item.getValue().getText().toString();
                boolean number=(item.getValue().getInputType()&InputType.TYPE_MASK_CLASS)==InputType.TYPE_CLASS_NUMBER;
                Object value=text;
                if(number) {
                    try { value=Integer.parseInt(text.trim()); }
                    catch(NumberFormatException e) { invalid(path,"Enter a whole number.");return; }
                } else if(!path.endsWith("password") && !path.endsWith("passkey") && !path.endsWith("preSharedKey") && !path.endsWith("username")) value=text.trim();
                put(edits,path,value);
            }
            for(Map.Entry<String,CheckBox> item:checks.entrySet()) put(edits,item.getKey(),item.getValue().isChecked());
            put(edits,"tv.driver",driver.getSelectedItemPosition()==0?"sony-bravia":"nvidia-shield");
            if(driver.getSelectedItemPosition()==1) {
                put(edits,"tv.controlMethod",shieldMethod.getSelectedItemPosition()==1?"adb":"native");
                put(edits,"tv.volumeControl",shieldVolume.getSelectedItemPosition()==1?"device":"system");
                if(shieldMethod.getSelectedItemPosition()==1) put(edits,"tv.adb.enabled",true);
            }
            JSONObject candidate=ConfigEditor.merge(original,edits);
            candidate.getJSONObject("tv").remove("volumeTarget"); // Remove the withdrawn external-volume setting.
            Credentials.ensurePasskey(candidate);
            try { new AppConfig(candidate,DeviceName.read(this)); }
            catch(IllegalArgumentException e) { error.setText(e.getMessage());error.setVisibility(View.VISIBLE);return; }
            catch(Exception e) { error.setText("Check the broker URI, client ID, driver and API fields.");error.setVisibility(View.VISIBLE);return; }
            ConfigStore.save(this,candidate.toString(2).getBytes(StandardCharsets.UTF_8));
            if(pendingIdentity!=null) ServerIdentity.requestActivation(getFilesDir(),pendingIdentity);
            getSharedPreferences("service",0).edit().putString("status","Applying settings").apply();
            startForegroundService(new Intent(this,MqttService.class).setAction(MqttService.RELOAD));
            Toast.makeText(this,"Settings saved; applying connections",Toast.LENGTH_LONG).show();finish();
        } catch(Exception e) {
            error.setText("Settings could not be applied. Your saved file and available storage should be checked.");
            error.setVisibility(View.VISIBLE);
        }
    }
    private void invalid(String path,String message) {
        select(path.startsWith("mqtt.")?0:path.startsWith("api.")?1:2);
        if(path.startsWith("mqtt.tls.") || path.equals("mqtt.keepAliveSeconds")) mqttAdvanced.setVisibility(View.VISIBLE);
        fields.get(path).setError(message);fields.get(path).requestFocus();
    }
    private static void put(JSONObject root,String path,Object value) throws JSONException {
        String[] keys=path.split("\\.");JSONObject current=root;
        for(int i=0;i<keys.length-1;i++) {
            JSONObject next=current.optJSONObject(keys[i]);
            if(next==null) { next=new JSONObject();current.put(keys[i],next); }current=next;
        }
        current.put(keys[keys.length-1],value);
    }
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        for(Map.Entry<String,EditText> item:fields.entrySet()) state.putString(item.getKey(),item.getValue().getText().toString());
        for(Map.Entry<String,CheckBox> item:checks.entrySet()) state.putBoolean(item.getKey(),item.getValue().isChecked());
        if(driver!=null) state.putInt("driver",driver.getSelectedItemPosition());state.putInt("section",selected);
        state.putString("pending_identity",pendingIdentity);
        if(shieldMethod!=null) state.putInt("shield_method",shieldMethod.getSelectedItemPosition());
        if(shieldVolume!=null) state.putInt("shield_volume",shieldVolume.getSelectedItemPosition());
    }
    private void credentialButtons(LinearLayout parent,EditText value,String title,boolean generate) {
        LinearLayout row=new LinearLayout(this);
        Button qr=button("Show QR",()->QrActivity.show(this,title,value.getText().toString(),
                generate?"Scan to copy the API passkey. Regenerated values take effect with Save & apply.":
                        "Scan to copy the manually entered credential. Its value must match the broker or TV settings."));
        qr.setId(title.equals("API passkey")?R.id.api_passkey_qr:
                title.equals("MQTT password")?R.id.mqtt_password_qr:R.id.tv_psk_qr);
        row.addView(qr,new LinearLayout.LayoutParams(0,dp(50),1));
        if(generate) {
            Button regenerate=button("Regenerate",()->{
                value.setText(Credentials.generatePasskey());
                Toast.makeText(this,"New API passkey ready. Save & apply to rotate it.",Toast.LENGTH_LONG).show();
            });
            regenerate.setId(R.id.api_passkey_regenerate);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(50),1);lp.leftMargin=dp(10);
            row.addView(regenerate,lp);
        }
        parent.addView(row);
    }
    private void httpsIdentity(LinearLayout parent) {
        TextView heading=label("HTTPS key pair",20,ink);heading.setPadding(0,dp(12),0,0);parent.addView(heading);
        identityStatus=label(pendingIdentity==null?"Private key stays on this TV.": "New key ready; Save & apply to activate.",14,muted);
        parent.addView(identityStatus);
        LinearLayout row=new LinearLayout(this);
        Button publicKey=button("Public key QR",()->identity(false,false));publicKey.setId(R.id.https_public_qr);
        Button certificate=button("Certificate QR",()->identity(false,true));certificate.setId(R.id.https_certificate_qr);
        row.addView(publicKey,new LinearLayout.LayoutParams(0,dp(50),1));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(50),1);lp.leftMargin=dp(10);
        row.addView(certificate,lp);
        LinearLayout.LayoutParams publicRowParams=new LinearLayout.LayoutParams(-1,-2);publicRowParams.topMargin=dp(8);
        parent.addView(row,publicRowParams);
        Button regenerate=button("Regenerate HTTPS key pair",()->identity(true,false));regenerate.setId(R.id.https_regenerate);
        LinearLayout.LayoutParams regenerateParams=new LinearLayout.LayoutParams(-1,dp(50));
        regenerateParams.topMargin=dp(12);regenerateParams.bottomMargin=dp(8);parent.addView(regenerate,regenerateParams);
        parent.addView(label("Regeneration prepares a new private key and self-signed certificate. Save & apply activates them. Public-key pins must then be updated.",14,muted));
    }
    private void identity(boolean rotate,boolean certificate) {
        if(identityBusy) return;
        identityBusy=true;saveButton.setEnabled(false);
        identityStatus.setText(rotate?"Generating a new HTTPS key pair...":"Loading public identity...");
        keyWork.execute(()->{
            try {
                String candidate=rotate?ServerIdentity.prepareRotation(getFilesDir()):pendingIdentity;
                JSONObject info=ServerIdentity.publicInfo(getFilesDir(),candidate);
                runOnUiThread(()->{
                    if(isFinishing() || isDestroyed()) return;
                    identityBusy=false;saveButton.setEnabled(true);
                    if(rotate) pendingIdentity=candidate;
                    identityStatus.setText(pendingIdentity==null?"Private key stays on this TV.":"New key ready; Save & apply to activate.");
                    if(!rotate) QrActivity.show(this,certificate?"HTTPS certificate":"HTTPS public key",
                            info.optString(certificate?"certificate_pem":"public_key_pem"),
                            (pendingIdentity==null?"Current identity.":"Prepared identity; Save & apply to activate.")+
                                    "\nSHA-256: "+info.optString(certificate?"certificate_sha256":"public_key_sha256"));
                });
            } catch(Exception e) {
                runOnUiThread(()->{
                    if(isFinishing() || isDestroyed()) return;
                    identityBusy=false;saveButton.setEnabled(true);
                    identityStatus.setText("Key operation did not complete. Wait for pending settings to apply, then retry.");
                });
            }
        });
    }
    protected void onDestroy() { keyWork.shutdown();super.onDestroy(); }
    private LinearLayout column() { LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);return layout; }
    private TextView label(String value,int size,int color) {
        TextView text=new TextView(this);text.setText(value);text.setTextSize(size);text.setTextColor(color);return text;
    }
    private Button button(String title,Runnable action) {
        Button button=new Button(this);button.setText(title);button.setAllCaps(false);button.setTextSize(17);button.setTextColor(ink);
        button.setOnClickListener(view->action.run());
        button.setOnFocusChangeListener((view,focused)->paint(button,focused));paint(button,false);return button;
    }
    private void paint(Button button,boolean focused) {
        GradientDrawable bg=new GradientDrawable();bg.setCornerRadius(dp(6));bg.setColor(Color.rgb(33,61,70));
        bg.setStroke(dp(focused?3:1),focused?accent:Color.rgb(75,103,112));button.setBackground(bg);
    }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
}
