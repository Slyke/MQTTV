package net.phoxis.tvmqtt;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class SonyApi {
    private final AppConfig config;
    private final Map<String,String> remoteCodes = new HashMap<>();
    public SonyApi(AppConfig config) { this.config = config; }
    public JSONArray call(String service, String method, JSONArray params) throws Exception {
        byte[] payload = Jsons.object("method", method, "params", params, "id", 1, "version", "1.0").toString().getBytes(StandardCharsets.UTF_8);
        byte[] raw = post("/sony/" + service, "application/json; charset=UTF-8", payload, null);
        JSONObject reply = new JSONObject(new String(raw, StandardCharsets.UTF_8));
        JSONArray error = reply.optJSONArray("error");
        if (error != null) {
            String code = String.valueOf(error.opt(0));
            String message = error.optString(1, "Sony API error").replace(config.tvPsk, "[redacted]");
            if (message.length() > 160) message = message.substring(0, 160);
            throw new CommandFailure("sony_" + code, message);
        }
        JSONArray result = reply.optJSONArray("result");
        if (result == null) throw new CommandFailure("invalid_tv_reply", "TV returned an invalid API response");
        return result;
    }
    public JSONArray call(String service, String method) throws Exception { return call(service, method, new JSONArray()); }
    private byte[] post(String path, String contentType, byte[] payload, String soapAction) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)new URL(config.tvUrl + path).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(config.timeoutMs);
        connection.setReadTimeout(config.timeoutMs);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", contentType);
        connection.setRequestProperty("X-Auth-PSK", config.tvPsk);
        if (soapAction != null) connection.setRequestProperty("SOAPACTION", soapAction);
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(payload.length);
        try {
            try (OutputStream out = connection.getOutputStream()) { out.write(payload); }
            int status = connection.getResponseCode();
            if (status == 403 || status == 401) throw new CommandFailure("tv_authentication", "TV rejected the configured Sony key");
            if (status < 200 || status >= 300) throw new CommandFailure("tv_http_" + status, "TV returned HTTP " + status);
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > 512 * 1024) throw new IOException("TV response too large");
                    out.write(buffer, 0, n);
                }
                return out.toByteArray();
            }
        } catch (SocketTimeoutException | ConnectException e) {
            throw new CommandFailure("tv_unreachable", "TV did not respond before the request timeout");
        } finally { connection.disconnect(); }
    }
    public JSONObject snapshot() throws Exception {
        JSONObject power = call("system", "getPowerStatus").getJSONObject(0);
        String value = power.getString("status");
        JSONObject state = Jsons.object("reachable", true, "power", value, "state", "active".equals(value) ? "on" : "off",
                "volume", null, "muted", null, "input", null, "content", null);
        if ("active".equals(value)) {
            try {
                JSONObject speaker = speaker();
                state.put("volume", speaker.opt("volume"));
                state.put("muted", speaker.opt("mute"));
            } catch (CommandFailure e) { state.put("audio_error", e.code); }
            try {
                JSONObject content = call("avContent", "getPlayingContentInfo").getJSONObject(0);
                // Do not publish URLs that can contain provider tokens or signed query parameters.
                String uri = content.optString("uri");
                state.put("input", uri.startsWith("extInput:") ? uri : JSONObject.NULL);
                JSONObject current = new JSONObject();
                for (String key : new String[]{"source","title","programTitle","mediaType","playSpeed","dispNum","durationSec"})
                    if (content.has(key)) current.put(key, content.get(key));
                state.put("content", current);
            } catch (CommandFailure e) { state.put("content_error", e.code); }
        }
        return state;
    }
    public JSONObject speaker() throws Exception {
        JSONArray items = call("audio", "getVolumeInformation").getJSONArray(0);
        for (int i=0; i<items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            if ("speaker".equals(item.optString("target"))) return item;
        }
        throw new CommandFailure("audio_unavailable", "TV speaker volume is unavailable");
    }
    public void remoteKey(String name) throws Exception {
        if (remoteCodes.isEmpty()) {
            JSONArray keys = call("system", "getRemoteControllerInfo").getJSONArray(1);
            for (int i=0; i<keys.length(); i++) {
                JSONObject key=keys.getJSONObject(i);
                remoteCodes.put(key.getString("name"), key.getString("value"));
            }
        }
        String code = remoteCodes.get(name);
        if (code == null) throw new CommandFailure("unsupported_key", "This TV does not advertise that remote key");
        if (!code.matches("[A-Za-z0-9+/=]+")) throw new CommandFailure("invalid_ircc_code", "TV returned an invalid remote key");
        String xml = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:X_SendIRCC xmlns:u=\"urn:schemas-sony-com:service:IRCC:1\"><IRCCCode>" + code + "</IRCCCode></u:X_SendIRCC></s:Body></s:Envelope>";
        post("/sony/IRCC", "text/xml; charset=UTF-8", xml.getBytes(StandardCharsets.UTF_8),
                "\"urn:schemas-sony-com:service:IRCC:1#X_SendIRCC\"");
    }
    public JSONObject applications() throws Exception {
        JSONArray items = call("appControl", "getApplicationList").getJSONArray(0);
        JSONArray output = new JSONArray();
        for (int i=0; i<Math.min(items.length(), 80); i++) {
            JSONObject app=items.getJSONObject(i);
            output.put(Jsons.object("title", app.optString("title"), "uri", app.optString("uri")));
        }
        return Jsons.object("apps", output, "total", items.length());
    }
    public void launch(String value) throws Exception {
        JSONArray apps = call("appControl", "getApplicationList").getJSONArray(0);
        String found = null;
        for (int i=0; i<apps.length(); i++) {
            JSONObject app=apps.getJSONObject(i);
            if (value.equals(app.optString("uri")) || value.equalsIgnoreCase(app.optString("title"))) {
                found=app.getString("uri"); break;
            }
        }
        if (found == null) throw new CommandFailure("app_not_found", "Use an application title or URI returned by the apps command");
        call("appControl", "setActiveApp", new JSONArray().put(Jsons.object("uri", found)));
    }
}
