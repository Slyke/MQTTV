package net.phoxis.tvmqtt;

import org.json.JSONObject;
import java.net.URI;

public final class AppConfig {
    public final boolean textAdbEnabled;
    public final int textAdbPort;
    public final String textAdbKeyFile;
    public final String uri, clientId, username, password, baseTopic, commandTopic, ackTopic, stateTopic, availabilityTopic;
    public final String tvVolumeControl, tvControlMethod, tvDriver, tvUrl, tvPsk, apiPasskey, apiBindAddress, caFile, caPem;
    public final int keepAlive, timeoutMs, dedupEntries, maximumAge, pollSeconds, memoryThresholdMb, heartbeatSeconds, apiHttpPort, apiHttpsPort, apiMaxConnections, apiMaxWebSockets, apiRequestTimeoutMs;
    public final boolean tls, insecureTLS, trustSystemCAs, startOnBoot, keepCpuAwake, keepWifiAwake, stateRetain, apiHttpEnabled, apiHttpsEnabled;

    public AppConfig(JSONObject root) throws Exception { this(root,"Android TV"); }

    public AppConfig(JSONObject root,String deviceName) throws Exception {
        JSONObject mqtt = root.getJSONObject("mqtt");
        String suppliedUri = required(mqtt, "uri", 512);
        URI parsed = new URI(suppliedUri);
        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(java.util.Locale.ROOT);
        tls = "ssl".equals(scheme) || "tls".equals(scheme) || "mqtts".equals(scheme);
        if ((!tls && !"tcp".equals(scheme) && !"mqtt".equals(scheme)) || parsed.getHost() == null ||
                parsed.getUserInfo() != null || parsed.getQuery() != null || parsed.getFragment() != null ||
                (parsed.getPath() != null && !parsed.getPath().isEmpty()) ||
                parsed.getPort() == 0 || parsed.getPort() > 65535)
            throw new IllegalArgumentException("mqtt.uri needs mqtt://host:port or mqtts://host:port");
        uri = (tls ? "ssl://" : "tcp://") + parsed.getRawAuthority();
        clientId = required(mqtt, "clientId", 128);
        username = optional(mqtt, "username", "", 256);
        password = optional(mqtt, "password", "", 2048);
        baseTopic = DeviceTopics.base(mqtt.isNull("baseTopic")?"":optional(mqtt,"baseTopic","",512),deviceName);
        commandTopic = baseTopic + "/cmnd/COMMAND";
        ackTopic = baseTopic + "/stat/RESULT";
        stateTopic = baseTopic + "/tele/STATE";
        availabilityTopic = baseTopic + "/tele/LWT";
        java.util.Set<String> topics = new java.util.HashSet<>(java.util.Arrays.asList(
                commandTopic, ackTopic, stateTopic, availabilityTopic));
        if (topics.size() != 4) throw new IllegalArgumentException("MQTT topics must all be different");
        keepAlive = integer(mqtt, "keepAliveSeconds", 30, 10, 600);
        JSONObject ssl = mqtt.optJSONObject("tls");
        if (ssl == null) ssl = new JSONObject();
        insecureTLS = bool(ssl, "insecureSkipVerify", false);
        trustSystemCAs = bool(ssl, "trustSystemCAs", true);
        caFile = optional(ssl, "caFile", "", 128);
        caPem = optional(ssl, "caCertificatePem", "", 65536);
        if (!caFile.isEmpty() && (!caFile.matches("[A-Za-z0-9][A-Za-z0-9._-]*") || caFile.contains("..")))
            throw new IllegalArgumentException("tls.caFile must be a file name inside the app's files directory");
        if (tls && !insecureTLS && !trustSystemCAs && caFile.isEmpty() && caPem.isEmpty())
            throw new IllegalArgumentException("TLS needs system trust or a custom CA certificate");
        // Accept the first prototype's section while migrating existing installations.
        JSONObject control = root.optJSONObject("tv");
        if (control == null) control = root.getJSONObject("sony");
        tvDriver = optional(control, "driver", "sony-bravia", 64);
        boolean sonyDriver="sony-bravia".equals(tvDriver);
        if(!sonyDriver && !"nvidia-shield".equals(tvDriver))
            throw new IllegalArgumentException("Unsupported tv.driver; use sony-bravia or nvidia-shield");
        tvUrl=sonyDriver?required(control,"baseUrl",256).replaceAll("/+$",""):optional(control,"baseUrl","",256);
        if(sonyDriver) {
            URI tv=new URI(tvUrl);
            if(!"http".equals(tv.getScheme()) || tv.getHost()==null || tv.getUserInfo()!=null ||
                    tv.getQuery()!=null || tv.getFragment()!=null || !tv.getPath().isEmpty())
                throw new IllegalArgumentException("tv.baseUrl must be http://host[:port]");
        }
        tvPsk=sonyDriver?required(control,control.has("preSharedKey")?"preSharedKey":"psk",128):"";
        timeoutMs = integer(control, "timeoutMs", 5000, 500, 15000);
        JSONObject textAdb=control.optJSONObject("adb");
        if(textAdb==null) textAdb=control.optJSONObject("textAdb");
        if(textAdb==null) textAdb=new JSONObject();
        tvVolumeControl=optional(control,"volumeControl","system",32);
        if(!tvVolumeControl.equals("system") && !tvVolumeControl.equals("device"))
            throw new IllegalArgumentException("tv.volumeControl must be system or device");
        tvControlMethod=optional(control,"controlMethod","native",32);
        if(!tvControlMethod.equals("native") && !tvControlMethod.equals("adb"))
            throw new IllegalArgumentException("tv.controlMethod must be native or adb");
        boolean developer=sonyDriver || tvControlMethod.equals("adb");
        textAdbEnabled=sonyDriver?bool(textAdb,"enabled",false):tvControlMethod.equals("adb");
        textAdbPort=developer?integer(textAdb,"port",5555,1,65535):5555;
        textAdbKeyFile=developer?optional(textAdb,"keyFile",sonyDriver?"text-adbkey.pem":"adbkey.pem",128):"text-adbkey.pem";
        if(!textAdbKeyFile.matches("[A-Za-z0-9][A-Za-z0-9._-]*") || textAdbKeyFile.contains(".."))
            throw new IllegalArgumentException("tv.textAdb.keyFile must be an app-private file name");
        JSONObject api = root.optJSONObject("api");
        if (api == null) api = new JSONObject();
        apiPasskey = optional(api, "passkey", "", 512);
        apiBindAddress = optional(api, "bindAddress", "0.0.0.0", 128);
        JSONObject http = api.optJSONObject("http"), https = api.optJSONObject("https");
        if (http == null) http = new JSONObject();
        if (https == null) https = new JSONObject();
        apiHttpEnabled = bool(http, "enabled", false);
        apiHttpsEnabled = bool(https, "enabled", false);
        apiHttpPort = integer(http, "port", 8788, 1, 65535);
        apiHttpsPort = integer(https, "port", 8789, 1, 65535);
        apiMaxConnections = integer(api, "maxConnections", 16, 2, 64);
        apiMaxWebSockets = integer(api, "maxWebSockets", Math.min(8,apiMaxConnections-1), 1, apiMaxConnections - 1);
        apiRequestTimeoutMs = integer(api, "requestTimeoutMs", 20000, 1000, 60000);
        if (apiHttpEnabled || apiHttpsEnabled) {
            if (apiPasskey.isEmpty() || !apiPasskey.matches("[!-~]+"))
                throw new IllegalArgumentException("Enabled API requires a printable ASCII api.passkey without spaces");
            if (apiBindAddress.trim().isEmpty())
                throw new IllegalArgumentException("api.bindAddress must be set");
            if (apiHttpEnabled && apiHttpsEnabled && apiHttpPort == apiHttpsPort)
                throw new IllegalArgumentException("HTTP and HTTPS ports must differ");
        }
        JSONObject service = root.optJSONObject("service");
        if (service == null) service = new JSONObject();
        startOnBoot = bool(service, "startOnBoot", true);
        keepCpuAwake = bool(service, "keepCpuAwake", true);
        keepWifiAwake = bool(service, "keepWifiAwake", true);
        dedupEntries = integer(service, "deduplicationEntries", 256, 16, 4096);
        maximumAge = integer(service, "maximumCommandAgeSeconds", 120, 0, 86400);
        pollSeconds = integer(service, "statePollSeconds", 5, 2, 3600);
        memoryThresholdMb = integer(service, "memoryChangeThresholdMb", 8, 1, 1024);
        heartbeatSeconds = integer(service, "stateHeartbeatSeconds", Math.max(60, pollSeconds), 0, 86400);
        if (heartbeatSeconds > 0 && heartbeatSeconds < pollSeconds)
            throw new IllegalArgumentException("stateHeartbeatSeconds must be 0 or at least statePollSeconds");
        stateRetain = bool(service, "stateRetain", true);
    }

    public int staleSeconds() { return heartbeatSeconds == 0 ? 0 : Math.max(heartbeatSeconds * 3, keepAlive * 3); }

    private static String required(JSONObject object, String key, int max) throws Exception {
        String value = object.getString(key);
        if (value.trim().isEmpty() || value.length() > max || value.indexOf(0) >= 0)
            throw new IllegalArgumentException("Invalid configuration field: " + key);
        return value;
    }
    private static String optional(JSONObject object, String key, String fallback, int max) throws Exception {
        if (!object.has(key)) return fallback;
        String value = object.getString(key);
        if (value.length() > max || value.indexOf(0) >= 0) throw new IllegalArgumentException("Invalid configuration field: " + key);
        return value;
    }
    private static int integer(JSONObject object, String key, int fallback, int min, int max) throws Exception {
        if (!object.has(key)) return fallback;
        Object raw = object.get(key);
        if (!(raw instanceof Number) || ((Number)raw).doubleValue() != ((Number)raw).intValue())
            throw new IllegalArgumentException(key + " must be an integer");
        int value = ((Number)raw).intValue();
        if (value < min || value > max) throw new IllegalArgumentException(key + " is outside its allowed range");
        return value;
    }
    private static boolean bool(JSONObject object, String key, boolean fallback) throws Exception {
        if (!object.has(key)) return fallback;
        Object value = object.get(key);
        if (!(value instanceof Boolean)) throw new IllegalArgumentException(key + " must be a boolean");
        return (Boolean)value;
    }
}
