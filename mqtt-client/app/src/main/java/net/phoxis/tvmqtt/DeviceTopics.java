package net.phoxis.tvmqtt;

import java.text.Normalizer;
import java.util.Locale;

public final class DeviceTopics {
    private DeviceTopics() {}
    public static String slug(String name) {
        String value=Normalizer.normalize(name==null?"":name,Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+","").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+","-").replaceAll("^-+|-+$","");
        if(value.length()>64) value=value.substring(0,64).replaceAll("-+$","");
        return value.isEmpty()?"android-tv":value;
    }
    public static String base(String supplied,String deviceName) {
        String value=supplied.trim();
        if(value.isEmpty()) return "tvs/"+slug(deviceName);
        value=value.replaceAll("/+$","");
        if(value.isEmpty() || value.startsWith("/") || value.length()>512 || value.indexOf(0)>=0 ||
                value.contains("#") || value.contains("+"))
            throw new IllegalArgumentException("mqtt.baseTopic must be a literal topic without a leading / or wildcards");
        return value;
    }
}
