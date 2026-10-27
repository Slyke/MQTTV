package net.phoxis.tvmqtt;

import org.json.*;
import java.nio.charset.StandardCharsets;
import java.time.*;

public final class CommandProcessor {
    public interface Runner { JSONObject execute(JSONObject command) throws Exception; }
    private final CommandJournal journal;
    private final Runner runner;
    private final int maximumAge;
    public CommandProcessor(CommandJournal journal, Runner runner, int maximumAge) {
        this.journal = journal; this.runner = runner; this.maximumAge = maximumAge;
    }
    public JSONObject handle(byte[] payload, boolean retained, String receivedAt) throws Exception {
        return handle(payload,retained,receivedAt,true);
    }
    public JSONObject handle(byte[] payload,boolean retained,String receivedAt,boolean publishAck) throws Exception {
        String id = null, action = null;
        JSONObject command;
        try {
            if (payload.length > 16384) throw new CommandFailure("payload_too_large", "Commands are limited to 16 KiB");
            JSONTokener parser = new JSONTokener(new String(payload, StandardCharsets.UTF_8));
            Object parsed = parser.nextValue();
            if (!(parsed instanceof JSONObject) || parser.nextClean() != 0)
                throw new CommandFailure("invalid_json", "Command must contain exactly one JSON object");
            command = (JSONObject)parsed;
            Object rawId = command.opt("id");
            if (rawId instanceof String && ((String)rawId).matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) id = (String)rawId;
            Object rawAction = command.opt("action");
            if (rawAction instanceof String && ((String)rawAction).matches("[a-z][a-z_]{0,39}")) action = (String)rawAction;
            if (id == null) throw new CommandFailure("invalid_id", "A command needs an id containing 1-128 letters, numbers, dots, underscores, colons or hyphens");
            if (action == null) throw new CommandFailure("invalid_action", "A command needs a lowercase action name");
            if (retained) throw new CommandFailure("retained_command", "Retained commands are rejected");
        } catch (Exception error) {
            JSONObject ack = failure(id, action, receivedAt, error);
            if(publishAck) journal.enqueue(ack);
            return ack;
        }
        String hash = contentHash(command);
        JSONObject previous = journal.find(id);
        if (previous != null) {
            JSONObject ack;
            if (!hash.equals(previous.getString("hash"))) {
                ack = failure(id, action, receivedAt, new CommandFailure("id_conflict", "This command id was already used with different contents"));
            } else {
                ack = Jsons.copy(previous.getJSONObject("ack"));
                ack.put("duplicate", true);
                ack.put("timestamp", Jsons.now());
            }
            if(publishAck) journal.enqueue(ack);
            return ack;
        }
        try {
            Instant now = Instant.now();
            if (command.has("expires_at") && !Instant.parse(command.getString("expires_at")).isAfter(now))
                throw new CommandFailure("expired_command", "Command has expired");
            if (command.has("timestamp")) {
                Instant sent = Instant.parse(command.getString("timestamp"));
                if (sent.isAfter(now.plusSeconds(60)))
                    throw new CommandFailure("invalid_timestamp", "Command timestamp is too far in the future");
                if (maximumAge > 0 && sent.isBefore(now.minusSeconds(maximumAge)))
                    throw new CommandFailure("expired_command", "Command timestamp is too old");
            }
        } catch (Exception error) {
            JSONObject ack = failure(id, action, receivedAt, error instanceof CommandFailure ? error :
                    new CommandFailure("invalid_timestamp", "Timestamps must be ISO-8601 UTC timestamps"));
            if(publishAck) journal.enqueue(ack);
            return ack;
        }
        // Record intent before any side effect. A process death must never replay an uncertain key press.
        journal.begin(id, hash, action, receivedAt,publishAck);
        JSONObject ack;
        long started = System.nanoTime();
        try {
            JSONObject result = runner.execute(command);
            if (result.toString().getBytes(StandardCharsets.UTF_8).length > 32768)
                throw new CommandFailure("result_too_large", "Command result exceeds 32 KiB");
            ack = Jsons.object("command_id", id, "action", action, "ok", true, "result", result,
                    "received_at", receivedAt, "completed_at", Jsons.now(), "timestamp", Jsons.now());
        } catch (Exception error) {
            ack = failure(id, action, receivedAt, error);
        }
        ack.put("duration_ms", (System.nanoTime() - started) / 1000000);
        journal.finish(id, ack,publishAck);
        return ack;
    }
    public static String contentHash(JSONObject command) throws Exception {
        // Retry timestamps can change without changing the requested side effect.
        return Jsons.fingerprint(Jsons.object("action", command.getString("action"), "value", command.opt("value")));
    }
    private JSONObject failure(String id, String action, String receivedAt, Exception error) throws JSONException {
        String code = error instanceof CommandFailure ? ((CommandFailure)error).code : "command_failed";
        String message = error instanceof CommandFailure ? error.getMessage() : "Command could not be completed";
        return Jsons.object("command_id", id, "action", action, "ok", false, "received_at", receivedAt,
                "completed_at", Jsons.now(), "timestamp", Jsons.now(), "error", Jsons.object("code", code, "message", message));
    }
}
