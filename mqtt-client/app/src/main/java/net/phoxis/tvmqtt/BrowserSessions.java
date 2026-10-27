package net.phoxis.tvmqtt;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public final class BrowserSessions {
    static final String COOKIE="tvmqtt_session";
    static final long LIFETIME=30L*24*60*60;
    private final String key;
    public BrowserSessions(String key) { this.key=key; }
    String issue(long now) throws Exception {
        byte[] nonce=new byte[16];new SecureRandom().nextBytes(nonce);
        String body=(now+LIFETIME)+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        return body+"."+signature(body);
    }
    boolean valid(String value,long now) {
        if(key==null || key.isEmpty() || value==null || value.length()>160) return false;
        try {
            String[] parts=value.split("\\.",-1);
            if(parts.length!=3 || !parts[1].matches("[A-Za-z0-9_-]{22}") || !parts[2].matches("[A-Za-z0-9_-]{43}")) return false;
            long expires=Long.parseLong(parts[0]);
            return expires>now && expires<=now+LIFETIME+5 &&
                    MessageDigest.isEqual(signature(parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII),
                            parts[2].getBytes(StandardCharsets.US_ASCII));
        } catch(Exception e) { return false; }
    }
    boolean authorized(String cookie,long now) { return authorized(cookie,now,false); }
    boolean authorized(String cookie,long now,boolean secure) {
        if(cookie==null || cookie.length()>4096) return false;
        String found=null;
        for(String item:cookie.split(";")) {
            String[] pair=item.trim().split("=",2);
            if(pair.length==2 && (secure?"__Host-"+COOKIE:COOKIE).equals(pair[0])) {
                if(found!=null) return false;
                found=pair[1];
            }
        }
        return valid(found,now);
    }
    String cookie(boolean secure,long now) throws Exception {
        return (secure?"__Host-"+COOKIE:COOKIE)+"="+issue(now)+"; Path=/; Max-Age="+LIFETIME+"; HttpOnly; SameSite=Strict"+(secure?"; Secure":"");
    }
    private String signature(String value) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(("tv-phone-session:"+value).getBytes(StandardCharsets.US_ASCII)));
    }
    static boolean sameOrigin(String origin,String host,boolean secure) {
        if(origin==null || host==null) return false;
        try {
            URI parsed=new URI(origin);
            return (secure?"https":"http").equals(parsed.getScheme()) && parsed.getUserInfo()==null &&
                    host.equalsIgnoreCase(parsed.getRawAuthority()) && (parsed.getRawPath()==null || parsed.getRawPath().isEmpty()) &&
                    parsed.getQuery()==null && parsed.getFragment()==null;
        } catch(Exception e) { return false; }
    }
}
