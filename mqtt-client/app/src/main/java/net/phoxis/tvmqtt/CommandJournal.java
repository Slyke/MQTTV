package net.phoxis.tvmqtt;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class CommandJournal {
    private final File file;
    private final int limit;
    private final LinkedHashMap<String, JSONObject> entries = new LinkedHashMap<>();
    private final List<JSONObject> outbox = new ArrayList<>();
    public CommandJournal(File file, int limit) throws Exception {
        this.file = file;
        this.limit = limit;
        if (file.isFile()) {
            if (file.length() > 8 * 1024 * 1024) throw new IOException("Command journal exceeds size limit");
            JSONObject stored = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            JSONArray records = stored.getJSONArray("entries");
            for (int i = 0; i < records.length(); i++) {
                JSONObject record = records.getJSONObject(i);
                entries.put(record.getString("id"), record);
            }
            JSONArray pending = stored.getJSONArray("outbox");
            for (int i = 0; i < pending.length(); i++) outbox.add(pending.getJSONObject(i));
        }
        boolean recovered = false;
        for (JSONObject entry : entries.values()) {
            if (entry.isNull("ack")) {
                JSONObject ack = Jsons.object("command_id", entry.getString("id"), "action", entry.getString("action"),
                        "received_at", entry.getString("received_at"), "completed_at", Jsons.now(), "timestamp", Jsons.now(),
                        "ok", false, "error", Jsons.object("code", "execution_interrupted",
                        "message", "The service stopped during this command; its execution is uncertain and it was not repeated"));
                entry.put("ack", ack);
                if(entry.optBoolean("publish_ack",true)) append(ack);
                recovered = true;
            }
        }
        if (recovered) save();
    }
    public JSONObject find(String id) throws JSONException {
        return entries.containsKey(id) ? Jsons.copy(entries.get(id)) : null;
    }
    public void ensureCapacity() throws IOException {
        if (outbox.size() >= 512) throw new IOException("Acknowledgement outbox is full");
    }
    public void begin(String id, String hash, String action, String receivedAt) throws Exception {
        begin(id,hash,action,receivedAt,true);
    }
    public void begin(String id,String hash,String action,String receivedAt,boolean publishAck) throws Exception {
        if(publishAck) ensureCapacity();
        entries.put(id, Jsons.object("id", id, "hash", hash, "action", action, "received_at", receivedAt, "ack", null, "publish_ack",publishAck));
        trim();
        save();
    }
    public void finish(String id, JSONObject ack) throws Exception {
        finish(id,ack,true);
    }
    public void finish(String id,JSONObject ack,boolean publishAck) throws Exception {
        entries.get(id).put("ack", Jsons.copy(ack));
        if(publishAck) append(ack);
        trim();
        save();
    }
    public void enqueue(JSONObject ack) throws Exception {
        ensureCapacity();
        append(ack);
        save();
    }
    private void append(JSONObject ack) throws JSONException {
        outbox.add(Jsons.object("token", UUID.randomUUID().toString(), "ack", Jsons.copy(ack)));
    }
    public List<JSONObject> pending() throws JSONException {
        List<JSONObject> copy = new ArrayList<>();
        for (JSONObject entry : outbox) copy.add(Jsons.copy(entry));
        return copy;
    }
    public void delivered(String token) throws Exception {
        for (Iterator<JSONObject> it = outbox.iterator(); it.hasNext();) {
            if (token.equals(it.next().getString("token"))) { it.remove(); save(); return; }
        }
    }
    private void trim() {
        while (entries.size() > limit) entries.remove(entries.keySet().iterator().next());
    }
    private void save() throws Exception {
        JSONObject data = Jsons.object("version", 1, "entries", new JSONArray(entries.values()), "outbox", new JSONArray(outbox));
        byte[] bytes = data.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 8 * 1024 * 1024) throw new IOException("Command journal exceeds size limit");
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create journal directory");
        File temporary = new File(parent, file.getName() + ".tmp");
        try (FileOutputStream stream = new FileOutputStream(temporary)) {
            stream.write(bytes);
            stream.getFD().sync();
        }
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
