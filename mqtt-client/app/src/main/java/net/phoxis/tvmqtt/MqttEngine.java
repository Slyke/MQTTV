package net.phoxis.tvmqtt;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class MqttEngine implements ControlBackend {
    public interface Listener { void status(String message); void fatal(String message); }
    private final AppConfig config;
    private final File directory;
    private final TvCommands.Snapshot source;
    private final Listener listener;
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1);
    private final AtomicInteger queued = new AtomicInteger();
    private final Set<String> inflight = new HashSet<>();
    private final CommandJournal journal;
    private final CommandProcessor processor;
    private volatile boolean running;
    private MqttAsyncClient client;
    private MqttConnectOptions options;
    private int reconnectDelay = 1;
    private JSONObject lastState, lastObservedState, lastEmittedState;
    private final CopyOnWriteArrayList<Consumer<JSONObject>> stateListeners=new CopyOnWriteArrayList<>();
    private final Set<CompletableFuture<JSONObject>> apiPending=ConcurrentHashMap.newKeySet();
    private long lastEmissionNanos;
    private boolean connecting, announced;
    private ScheduledFuture<?> reconnectTask;
    private String sessionId, willRegisteredAt;

    public MqttEngine(AppConfig config, File directory, CommandProcessor.Runner runner,
                      TvCommands.Snapshot source, Listener listener) throws Exception {
        this.config=config; this.directory=directory; this.source=source; this.listener=listener;
        journal = new CommandJournal(new File(directory,"command-journal.json"),config.dedupEntries);
        processor = new CommandProcessor(journal,runner,config.maximumAge);
        worker.setRemoveOnCancelPolicy(true);
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        worker.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }
    public void start() {
        running=true;
        enqueue(() -> {
            try {
                client = new MqttAsyncClient(config.uri,config.clientId,new MemoryPersistence());
                client.setManualAcks(true);
                options = new MqttConnectOptions();
                options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
                options.setAutomaticReconnect(false);
                options.setCleanSession(true);
                options.setConnectionTimeout(10);
                options.setKeepAliveInterval(config.keepAlive);
                options.setMaxInflight(10);
                if(!config.username.isEmpty()) options.setUserName(config.username);
                if(!config.password.isEmpty()) options.setPassword(config.password.toCharArray());
                if(config.tls) {
                    options.setSocketFactory(TlsSupport.create(config,directory));
                    options.setHttpsHostnameVerificationEnabled(!config.insecureTLS);
                }
                client.setCallback(new MqttCallbackExtended() {
                    public void connectComplete(boolean reconnect,String serverURI) { enqueue(() -> subscribe()); }
                    public void connectionLost(Throwable cause) {
                        enqueue(() -> { connecting=false; inflight.clear(); listener.status("Disconnected; reconnecting"); scheduleReconnect(); });
                    }
                    public void deliveryComplete(IMqttDeliveryToken token) {}
                    public void messageArrived(String topic,MqttMessage message) {
                        if(!running || !topic.equals(config.commandTopic)) return;
                        if(queued.incrementAndGet()>32) {
                            queued.decrementAndGet();
                            enqueue(() -> fail("Command queue is full; restart the service"));
                            return;
                        }
                        String receivedAt=Jsons.now();
                        byte[] payload=message.getPayload();
                        int packetId=message.getId(), qos=message.getQos();
                        boolean retained=message.isRetained();
                        enqueue(() -> {
                            try {
                                processor.handle(payload,retained,receivedAt);
                                // The durable journal/outbox is written before acknowledging MQTT delivery.
                                try { client.messageArrivedComplete(packetId,qos); } catch(MqttException ignored) {}
                                drain();
                                poll(true);
                            } catch(Exception e) {
                                fail("Command journal could not be saved; service stopped");
                            } finally { queued.decrementAndGet(); }
                        });
                    }
                });
                connect();
                worker.scheduleWithFixedDelay(() -> {
                    if(running) { poll(false); drain(); }
                },config.pollSeconds,config.pollSeconds,TimeUnit.SECONDS);
            } catch(Exception e) {
                fail(config.tls ? "Could not start MQTT; check configuration and CA certificate" : "Could not start MQTT; check configuration");
            }
        });
    }
    private void connect() {
        if(!running || connecting || client.isConnected()) return;
        if(reconnectTask!=null) { reconnectTask.cancel(false); reconnectTask=null; }
        listener.status("Connecting to MQTT");
        try {
            JSONObject snapshot;
            try { snapshot=sample(); } catch(Exception ignored) { snapshot=lastObservedState; }
            willRegisteredAt=Jsons.now();
            sessionId=UUID.randomUUID().toString();
            announced=false;
            // MQTT 3.1.1 registers a fixed will for this connection. Refresh it on EVERY reconnect.
            JSONObject will=Availability.message(config,false,"connection_lost",snapshot,sessionId,willRegisteredAt,true);
            options.setWill(config.availabilityTopic,will.toString().getBytes(StandardCharsets.UTF_8),1,true);
            connecting=true;
            client.connect(options,null,new IMqttActionListener() {
                public void onSuccess(IMqttToken token) { enqueue(() -> { connecting=false; reconnectDelay=1; }); }
                public void onFailure(IMqttToken token,Throwable error) {
                    enqueue(() -> { connecting=false; listener.status("Connection failed; retrying"); scheduleReconnect(); });
                }
            });
        } catch(Exception e) { connecting=false; scheduleReconnect(); }
    }
    private void scheduleReconnect() {
        if(!running || (reconnectTask!=null && !reconnectTask.isDone())) return;
        int delay=reconnectDelay;
        reconnectDelay=Math.min(128,reconnectDelay*2);
        reconnectTask=worker.schedule(() -> { reconnectTask=null; connect(); },delay,TimeUnit.SECONDS);
    }
    private void subscribe() {
        if(!running || !client.isConnected()) return;
        try {
            client.subscribe(config.commandTopic,1,null,new IMqttActionListener() {
                public void onSuccess(IMqttToken token) {
                    enqueue(() -> {
                        try {
                            inflight.clear();
                            if(!config.stateRetain) client.publish(config.stateTopic,new byte[0],1,true);
                            listener.status("Connected; waiting for commands");
                            drain();
                            poll(true);
                        } catch(Exception e) { listener.status("Connected; publication will retry"); }
                    });
                }
                public void onFailure(IMqttToken token,Throwable error) {
                    enqueue(() -> {
                        listener.status("Subscription failed; check topic permissions");
                        if(running) worker.schedule(() -> subscribe(),10,TimeUnit.SECONDS);
                    });
                }
            });
        } catch(MqttException e) { listener.status("Subscription interrupted; reconnecting"); }
    }
    private void drain() {
        if(!running || !client.isConnected()) return;
        try {
            for(JSONObject item:journal.pending()) {
                if(inflight.size()>=8) break;
                String token=item.getString("token");
                if(!inflight.add(token)) continue;
                byte[] bytes=item.getJSONObject("ack").toString().getBytes(StandardCharsets.UTF_8);
                try {
                    client.publish(config.ackTopic,bytes,1,false,null,new IMqttActionListener() {
                        public void onSuccess(IMqttToken ignored) {
                            enqueue(() -> {
                                try { journal.delivered(token); inflight.remove(token); drain(); }
                                catch(Exception e) { fail("Acknowledgement journal could not be saved"); }
                            });
                        }
                        public void onFailure(IMqttToken ignored,Throwable error) { enqueue(() -> inflight.remove(token)); }
                    });
                } catch(MqttException e) { inflight.remove(token); break; }
            }
        } catch(Exception e) { fail("Acknowledgement journal could not be read"); }
    }
    private JSONObject sample() throws Exception {
        JSONObject state=source.get();
        state.put("schema_version",2);
        state.put("device_id",config.clientId);
        state.put("control_driver",config.tvDriver);
        String timestamp=Jsons.now();
        state.put("timestamp",timestamp);
        state.put("stale_after_seconds",config.staleSeconds());
        state.put("expires_at",config.staleSeconds()==0?JSONObject.NULL:
                java.time.Instant.parse(timestamp).plusSeconds(config.staleSeconds()).toString());
        lastObservedState=Jsons.copy(state);
        return state;
    }
    private void poll(boolean force) {
        if(!running) return;
        try {
            JSONObject state=sample();
            boolean heartbeat=config.heartbeatSeconds>0 && (lastEmissionNanos==0 ||
                    System.nanoTime()-lastEmissionNanos>=config.heartbeatSeconds*1000000000L);
            if(force || heartbeat || StateChanges.changed(lastEmittedState,state,config.memoryThresholdMb)) {
                JSONObject published=Jsons.copy(state);
                lastEmittedState=Jsons.copy(state);
                lastEmissionNanos=System.nanoTime();
                for(Consumer<JSONObject> consumer:stateListeners) {
                    try { consumer.accept(Jsons.copy(state)); } catch(RuntimeException ignored) {}
                }
                if(client==null || !client.isConnected()) return;
                client.publish(config.stateTopic,state.toString().getBytes(StandardCharsets.UTF_8),1,config.stateRetain,null,new IMqttActionListener() {
                    public void onSuccess(IMqttToken token) {
                        enqueue(() -> {
                            lastState=published;
                            if(running && client.isConnected()) {
                                try {
                                    JSONObject availability=Availability.message(config,true,announced?"state_updated":"connected",
                                            published,sessionId,willRegisteredAt,false);
                                    client.publish(config.availabilityTopic,availability.toString().getBytes(StandardCharsets.UTF_8),1,true);
                                    announced=true;
                                } catch(Exception ignored) {}
                            }
                        });
                    }
                    public void onFailure(IMqttToken token,Throwable error) { enqueue(() -> lastState=null); }
                });
            }
        } catch(Exception e) { listener.status("Connected; state refresh failed and will retry"); }
    }
    public void addStateListener(Consumer<JSONObject> listener) { stateListeners.add(listener); }
    public void removeStateListener(Consumer<JSONObject> listener) { stateListeners.remove(listener); }
    public CompletableFuture<JSONObject> currentState() {
        return submitApi(null);
    }
    public CompletableFuture<JSONObject> apiCommand(byte[] payload) {
        if(payload==null || payload.length>16384) {
            CompletableFuture<JSONObject> rejected=new CompletableFuture<>();
            rejected.completeExceptionally(new IllegalArgumentException("Command exceeds 16 KiB"));
            return rejected;
        }
        return submitApi(payload.clone());
    }
    private CompletableFuture<JSONObject> submitApi(byte[] payload) {
        CompletableFuture<JSONObject> result=new CompletableFuture<>();
        if(!running || worker.isShutdown()) {
            result.completeExceptionally(new RejectedExecutionException("Service is stopped"));
            return result;
        }
        if(queued.incrementAndGet()>32) {
            queued.decrementAndGet();
            result.completeExceptionally(new RejectedExecutionException("Control queue is full"));
            return result;
        }
        apiPending.add(result);
        result.whenComplete((value,error) -> apiPending.remove(result));
        try {
            worker.execute(() -> {
                try {
                    if(result.isCancelled() || !running) return;
                    if(payload==null) {
                        result.complete(sample());
                    } else {
                        // All transports use the same serialized processor and persistent ID cache.
                        // API acknowledgements are returned directly and do not fill the MQTT outbox.
                        JSONObject ack=processor.handle(payload,false,Jsons.now(),false);
                        result.complete(ack);
                        drain();
                        poll(true);
                    }
                } catch(Exception error) {
                    result.completeExceptionally(error);
                    if(payload!=null) fail("Command journal could not be saved; service stopped");
                } finally { queued.decrementAndGet(); }
            });
        } catch(RejectedExecutionException error) {
            queued.decrementAndGet();result.completeExceptionally(error);
        }
        return result;
    }
    private void fail(String message) {
        if(!running) return;
        running=false;
        listener.fatal(message);
    }
    private void enqueue(Runnable task) {
        if(worker.isShutdown()) return;
        try { worker.execute(task); } catch(RejectedExecutionException ignored) {}
    }
    public boolean awaitStopped(long timeoutMs) throws InterruptedException {
        return worker.awaitTermination(timeoutMs,TimeUnit.MILLISECONDS);
    }
    public void stop() {
        running=false;
        for(CompletableFuture<JSONObject> pending:apiPending)
            pending.completeExceptionally(new RejectedExecutionException("Service stopped"));
        stateListeners.clear();
        if(reconnectTask!=null) reconnectTask.cancel(false);
        enqueue(() -> {
            if(client!=null) {
                try {
                    if(client.isConnected()) {
                        JSONObject offline=Availability.message(config,false,"stopped",lastState==null?lastObservedState:lastState,
                                sessionId,willRegisteredAt,false);
                        client.publish(config.availabilityTopic,offline.toString().getBytes(StandardCharsets.UTF_8),1,true).waitForCompletion(1000);
                        client.disconnect(1000).waitForCompletion(1500);
                    }
                } catch(Exception ignored) {}
                try { client.close(true); } catch(MqttException ignored) {}
            }
        });
        worker.shutdown();
    }
}
