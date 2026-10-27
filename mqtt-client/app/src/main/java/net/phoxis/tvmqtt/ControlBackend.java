package net.phoxis.tvmqtt;
import org.json.JSONObject;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface ControlBackend {
    CompletableFuture<JSONObject> apiCommand(byte[] payload);
    CompletableFuture<JSONObject> currentState();
    void addStateListener(Consumer<JSONObject> listener);
    void removeStateListener(Consumer<JSONObject> listener);
}
