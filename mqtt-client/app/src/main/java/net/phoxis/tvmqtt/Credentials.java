package net.phoxis.tvmqtt;

import java.security.SecureRandom;
import java.util.Base64;
import org.json.JSONObject;

public final class Credentials {
    private Credentials() {}
    public static String generatePasskey() {
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public static boolean ensurePasskey(JSONObject root) throws Exception {
        JSONObject api=root.optJSONObject("api");
        if(api==null) { api=new JSONObject();root.put("api",api); }
        if(!api.has("passkey") || api.isNull("passkey") || api.getString("passkey").isEmpty()) {
            api.put("passkey",generatePasskey());return true;
        }
        return false;
    }
}
