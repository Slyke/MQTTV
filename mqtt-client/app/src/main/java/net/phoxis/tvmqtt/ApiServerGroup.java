package net.phoxis.tvmqtt;

import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class ApiServerGroup {
    private final AppConfig config;
    private final ControlBackend backend;
    private final HttpsIdentity.Provider provider;
    private final ApiResources resources;
    final BrowserSessions browserSessions;
    final UiAssets assets;
    private final List<LocalApiServer> servers=new ArrayList<>();
    private volatile HttpsIdentity identity;
    private volatile boolean ready;
    private volatile String fingerprint;
    private final Consumer<JSONObject> stateListener;
    private void broadcastState(JSONObject state) {
        try {
            String payload=Jsons.object("state",state).toString();
            for(ApiResources.Peer peer:resources.peers) peer.state(payload);
        } catch(Exception ignored) {}
    }
    public ApiServerGroup(AppConfig config,ControlBackend backend,HttpsIdentity.Provider provider) {
        this(config,backend,provider,name->null);
    }
    public ApiServerGroup(AppConfig config,ControlBackend backend,HttpsIdentity.Provider provider,UiAssets assets) {
        this.config=config;this.backend=backend;this.provider=provider;resources=new ApiResources(config);
        this.assets=assets;browserSessions=new BrowserSessions(config.apiPasskey);
        stateListener=this::broadcastState;
    }
    synchronized HttpsIdentity identity() throws Exception {
        if(identity==null) {
            identity=provider.load();
            fingerprint=identity.publicInfo().optString("certificate_sha256");
        }
        return identity;
    }
    public synchronized void start() throws Exception {
        try {
            if(config.apiHttpEnabled) {
                LocalApiServer server=new LocalApiServer(config,backend,this,resources,config.apiHttpPort);
                servers.add(server); server.start(10000,true);
            }
            if(config.apiHttpsEnabled) {
                LocalApiServer server=new LocalApiServer(config,backend,this,resources,config.apiHttpsPort);
                server.makeSecure(identity().socketFactory(),new String[]{"TLSv1.2"});
                servers.add(server); server.start(10000,true);
            }
            backend.addStateListener(stateListener);
            resources.timers.scheduleWithFixedDelay(() -> {
                for(ApiResources.Peer peer:resources.peers) peer.ping();
            },15,15,TimeUnit.SECONDS);
            ready=true;
        } catch(Exception e) { stop(); throw e; }
    }
    public synchronized void stop() {
        ready=false;backend.removeStateListener(stateListener);
        for(LocalApiServer server:servers) server.stop();
        resources.closeAll();
    }
    public JSONObject metadata() throws Exception {
        return Jsons.object("ready",ready,"bind_address",config.apiBindAddress,
                "http_enabled",config.apiHttpEnabled,"http_port",config.apiHttpPort,
                "https_enabled",config.apiHttpsEnabled,"https_port",config.apiHttpsPort,
                "websocket_path","/api/ws","certificate_sha256",fingerprint);
    }
}
