package net.phoxis.tvmqtt;

import org.json.*;
import java.net.*;
import java.util.*;

public final class NetworkAddresses {
    private NetworkAddresses() {}
    public static JSONObject snapshot() throws Exception {
        JSONObject interfaces=new JSONObject(), ips=new JSONObject();
        Enumeration<NetworkInterface> enumeration=NetworkInterface.getNetworkInterfaces();
        List<NetworkInterface> sorted=enumeration==null?new ArrayList<>():Collections.list(enumeration);
        Collections.sort(sorted,Comparator.comparing(NetworkInterface::getName));
        for(NetworkInterface network:sorted) {
            String name=network.getName();
            JSONObject info=Jsons.object("display_name",network.getDisplayName(),"virtual",network.isVirtual());
            try {
                info.put("up",network.isUp()).put("loopback",network.isLoopback()).put("mtu",network.getMTU());
            } catch(SocketException e) { info.put("details_available",false); }
            List<InterfaceAddress> addresses=new ArrayList<>(network.getInterfaceAddresses());
            Collections.sort(addresses,Comparator.comparing(a -> a.getAddress().getHostAddress()));
            JSONArray details=new JSONArray(), values=new JSONArray();
            for(InterfaceAddress entry:addresses) {
                InetAddress address=entry.getAddress();
                String host=address.getHostAddress();
                String scope=address.isLoopbackAddress()?"loopback":address.isLinkLocalAddress()?"link_local":
                        address.isSiteLocalAddress()?"private":"global";
                details.put(Jsons.object("address",host,"family",address instanceof Inet4Address?"ipv4":"ipv6",
                        "prefix_length",entry.getNetworkPrefixLength(),"scope",scope));
                values.put(host);
            }
            info.put("addresses",details);
            interfaces.put(name,info);
            ips.put(name,values);
        }
        return Jsons.object("interfaces",interfaces,"ips",ips);
    }
    public static String primaryIp(JSONObject ips,String activeInterface) throws Exception {
        JSONArray candidates=ips.optJSONArray(activeInterface==null?"":activeInterface);
        String found=preferred(candidates);
        if(found!=null) return found;
        List<String> names=new ArrayList<>();
        Iterator<String> it=ips.keys();
        while(it.hasNext()) names.add(it.next());
        Collections.sort(names);
        for(String name:names) {
            if(name.equals("lo")) continue;
            found=preferred(ips.optJSONArray(name));
            if(found!=null) return found;
        }
        return null;
    }
    private static String preferred(JSONArray addresses) throws Exception {
        if(addresses==null) return null;
        String ipv6=null;
        for(int i=0;i<addresses.length();i++) {
            String value=addresses.getString(i);
            if(value.startsWith("127.") || value.equals("::1") || value.equals("0:0:0:0:0:0:0:1")) continue;
            if(value.indexOf(':')<0) return value;
            if(!value.toLowerCase(Locale.ROOT).startsWith("fe80:")) ipv6=value;
        }
        return ipv6;
    }
}
