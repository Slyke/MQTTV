package net.phoxis.tvmqtt;

import android.content.Context;
import android.net.*;
import android.net.wifi.*;
import android.os.Process;
import org.json.*;
import java.net.InetAddress;
import java.util.*;

public final class NetworkTelemetry {
    private NetworkTelemetry() {}
    public static JSONObject snapshot(Context context) throws Exception {
        JSONObject data;
        try { data=NetworkAddresses.snapshot(); }
        catch(Exception e) { data=Jsons.object("interfaces",new JSONObject(),"ips",new JSONObject(),"address_error","unavailable"); }
        ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        String activeInterface=null;
        if(manager!=null) {
            Network active=manager.getActiveNetwork();
            NetworkCapabilities caps=active==null?null:manager.getNetworkCapabilities(active);
            LinkProperties properties=active==null?null:manager.getLinkProperties(active);
            NetworkInfo activeInfo=manager.getActiveNetworkInfo();
            JSONArray transports=new JSONArray();
            if(caps!=null) {
                int[] types={NetworkCapabilities.TRANSPORT_WIFI,NetworkCapabilities.TRANSPORT_ETHERNET,
                    NetworkCapabilities.TRANSPORT_VPN,NetworkCapabilities.TRANSPORT_CELLULAR,NetworkCapabilities.TRANSPORT_BLUETOOTH};
                String[] names={"wifi","ethernet","vpn","cellular","bluetooth"};
                for(int i=0;i<types.length;i++) if(caps.hasTransport(types[i])) transports.put(names[i]);
            }
            data.put("connected",activeInfo!=null && activeInfo.isConnected());
            data.put("validated",caps!=null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
            data.put("metered",manager.isActiveNetworkMetered());
            data.put("transports",transports);
            JSONArray dns=new JSONArray(), routes=new JSONArray();
            if(properties!=null) {
                activeInterface=properties.getInterfaceName();
                List<String> servers=new ArrayList<>();
                for(InetAddress address:properties.getDnsServers()) servers.add(address.getHostAddress());
                Collections.sort(servers);
                for(String address:servers) dns.put(address);
                List<String> routeValues=new ArrayList<>();
                for(RouteInfo route:properties.getRoutes()) {
                    JSONObject item=Jsons.object("destination",route.getDestination().toString(),
                            "gateway",route.getGateway()==null?null:route.getGateway().getHostAddress(),
                            "interface",route.getInterface(),"default",route.isDefaultRoute());
                    routeValues.add(Jsons.canonical(item));
                }
                Collections.sort(routeValues);
                for(String value:routeValues) routes.put(new JSONObject(value));
            }
            data.put("dns_servers",dns).put("routes",routes);
        }
        data.put("active_interface",activeInterface==null?JSONObject.NULL:activeInterface);
        String primary=NetworkAddresses.primaryIp(data.getJSONObject("ips"),activeInterface);
        data.put("primary_ip",primary==null?JSONObject.NULL:primary);
        WifiManager wifi=(WifiManager)context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if(wifi!=null) {
            try {
                WifiInfo info=wifi.getConnectionInfo();
                if(info!=null && info.getNetworkId()!=-1) {
                    String ssid=info.getSSID();
                    if(ssid!=null && ssid.length()>=2 && ssid.startsWith("\"") && ssid.endsWith("\"")) ssid=ssid.substring(1,ssid.length()-1);
                    if("<unknown ssid>".equalsIgnoreCase(ssid)) ssid=null;
                    int rssi=info.getRssi();
                    JSONObject signal=Jsons.object("ssid",ssid,"rssi_dbm",rssi<=-127?null:rssi,
                            "signal_percent",rssi<=-127?null:WifiManager.calculateSignalLevel(rssi,101),
                            "link_speed_mbps",info.getLinkSpeed(),"frequency_mhz",info.getFrequency());
                    data.put("wifi",signal);
                }
            } catch(SecurityException ignored) { data.put("wifi",Jsons.object("available",false)); }
        }
        data.put("traffic",Jsons.object("rx_bytes",counter(TrafficStats.getTotalRxBytes()),
                "tx_bytes",counter(TrafficStats.getTotalTxBytes()),"rx_packets",counter(TrafficStats.getTotalRxPackets()),
                "tx_packets",counter(TrafficStats.getTotalTxPackets()),"app_rx_bytes",counter(TrafficStats.getUidRxBytes(Process.myUid())),
                "app_tx_bytes",counter(TrafficStats.getUidTxBytes(Process.myUid()))));
        return data;
    }
    private static Object counter(long value) { return value<0?JSONObject.NULL:value; }
}
