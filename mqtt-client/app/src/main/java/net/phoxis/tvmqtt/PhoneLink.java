package net.phoxis.tvmqtt;

import android.app.Activity;
import android.widget.Toast;
import java.net.URLEncoder;
import org.json.*;

public final class PhoneLink {
    private PhoneLink() {}
    static String url(AppConfig config,String address) throws Exception {
        if(!config.apiHttpEnabled && !config.apiHttpsEnabled) return null;
        String bind=config.apiBindAddress;
        String host=("0.0.0.0".equals(bind) || "::".equals(bind))?address:bind;
        if(host==null || host.isEmpty() || host.equals("::") || host.equals("::1") || host.startsWith("127.") ||
                host.equals("0.0.0.0") || host.contains("%") ||
                !(host.matches("[0-9.]+") || host.matches("[0-9a-fA-F:]+") && host.contains(":")))
            throw new IllegalArgumentException("Set the API listen address to 0.0.0.0 or the TV LAN IP, then Save & apply.");
        return (config.apiHttpsEnabled?"https":"http")+"://"+(host.contains(":")?"["+host+"]":host)+":"+
                (config.apiHttpsEnabled?config.apiHttpsPort:config.apiHttpPort)+"/#key="+URLEncoder.encode(config.apiPasskey,"UTF-8");
    }
    public static void open(Activity activity) {
        try {
            AppConfig config=ConfigStore.load(activity);
            JSONObject network=NetworkTelemetry.snapshot(activity);
            String ip=network.optString("primary_ip","");
            String link=url(config,ip);
            if(link==null) throw new IllegalArgumentException("Enable HTTP or HTTPS in API settings and Save & apply first.");
            QrActivity.show(activity,"Open on phone",link,"Scan to open the TV remote, already signed in. This link grants access to this TV.");
        } catch(IllegalArgumentException e) { Toast.makeText(activity,e.getMessage(),Toast.LENGTH_LONG).show(); }
        catch(Exception e) { Toast.makeText(activity,"Save API settings and connect the TV to the LAN first.",Toast.LENGTH_LONG).show(); }
    }
}
