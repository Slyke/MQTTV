package net.phoxis.tvmqtt;

import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

public final class Jsons {
    private Jsons() {}
    public static JSONObject object(Object... pairs) throws JSONException {
        JSONObject object = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) object.put((String)pairs[i], pairs[i + 1] == null ? JSONObject.NULL : pairs[i + 1]);
        return object;
    }
    public static String now() { return Instant.now().toString(); }
    public static String canonical(Object value) throws JSONException {
        if (value instanceof JSONObject) {
            JSONObject o = (JSONObject)value;
            List<String> keys = new ArrayList<>();
            Iterator<String> it = o.keys();
            while (it.hasNext()) keys.add(it.next());
            Collections.sort(keys);
            StringJoiner output = new StringJoiner(",", "{", "}");
            for (String key : keys) output.add(JSONObject.quote(key) + ":" + canonical(o.get(key)));
            return output.toString();
        }
        if (value instanceof JSONArray) {
            JSONArray a = (JSONArray)value;
            StringJoiner output = new StringJoiner(",", "[", "]");
            for (int i = 0; i < a.length(); i++) output.add(canonical(a.get(i)));
            return output.toString();
        }
        return value instanceof String ? JSONObject.quote((String)value) : String.valueOf(value);
    }
    public static String fingerprint(JSONObject value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical(value).getBytes(StandardCharsets.UTF_8));
        StringBuilder output = new StringBuilder();
        for (byte b : digest) output.append(String.format(Locale.ROOT, "%02x", b & 255));
        return output.toString();
    }
    public static JSONObject copy(JSONObject value) throws JSONException { return new JSONObject(value.toString()); }
}
