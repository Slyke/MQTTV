package net.phoxis.tvmqtt;

import fi.iki.elonen.NanoHTTPD;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

final class ApiResources implements NanoHTTPD.AsyncRunner {
    interface Peer { void state(String json); void ping(); void abort(); }
    final Set<Peer> peers=ConcurrentHashMap.newKeySet();
    final Semaphore websocketSlots;
    final ScheduledThreadPoolExecutor timers=new ScheduledThreadPoolExecutor(1);
    private final Set<NanoHTTPD.ClientHandler> handlers=ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor connections;
    ApiResources(AppConfig config) {
        websocketSlots=new Semaphore(config.apiMaxWebSockets);
        AtomicInteger sequence=new AtomicInteger();
        connections=new ThreadPoolExecutor(0,config.apiMaxConnections,30,TimeUnit.SECONDS,new SynchronousQueue<>(),r -> {
            Thread thread=new Thread(r,"tv-api-client-"+sequence.incrementAndGet()); thread.setDaemon(true); return thread;
        });
        timers.setRemoveOnCancelPolicy(true);
        timers.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }
    public void exec(NanoHTTPD.ClientHandler handler) {
        handlers.add(handler);
        try { connections.execute(handler); }
        catch(RejectedExecutionException e) { handlers.remove(handler); handler.close(); }
    }
    public void closed(NanoHTTPD.ClientHandler handler) { handlers.remove(handler); }
    public void closeAll() {
        for(Peer peer:peers) peer.abort();
        for(NanoHTTPD.ClientHandler handler:handlers) handler.close();
        connections.shutdownNow(); timers.shutdownNow();
    }
}
