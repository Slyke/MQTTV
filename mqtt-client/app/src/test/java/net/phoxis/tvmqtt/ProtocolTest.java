package net.phoxis.tvmqtt;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.json.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ProtocolTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private File file;
    private CommandJournal journal;
    private CommandProcessor processor;
    private AtomicInteger executed;
    @Before public void setup() throws Exception {
        file=new File(temp.getRoot(),"journal.json");
        journal=new CommandJournal(file,16);
        executed=new AtomicInteger();
        processor=new CommandProcessor(journal,c -> { executed.incrementAndGet(); return Jsons.object("accepted",true); },120);
    }
    private JSONObject send(String json,boolean retained) throws Exception {
        return processor.handle(json.getBytes(StandardCharsets.UTF_8),retained,Jsons.now());
    }
    @Test public void commandHasCorrelatedUtcTimestamps() throws Exception {
        JSONObject ack=send("{\"id\":\"phone-1\",\"action\":\"left\"}",false);
        assertTrue(ack.getBoolean("ok")); assertEquals("phone-1",ack.getString("command_id"));
        Instant.parse(ack.getString("received_at")); Instant.parse(ack.getString("completed_at")); Instant.parse(ack.getString("timestamp"));
        assertEquals(1,journal.pending().size()); assertEquals(1,executed.get());
    }
    @Test public void duplicateSurvivesProcessRestartAndJsonKeyOrdering() throws Exception {
        send("{\"id\":\"one\",\"action\":\"left\"}",false);
        CommandProcessor recovered=new CommandProcessor(new CommandJournal(file,16),c -> { executed.incrementAndGet(); return new JSONObject(); },120);
        JSONObject ack=recovered.handle("{\"action\":\"left\",\"id\":\"one\"}".getBytes(StandardCharsets.UTF_8),false,Jsons.now());
        assertTrue(ack.getBoolean("duplicate")); assertEquals(1,executed.get());
    }
    @Test public void retryCanRefreshTimestampWithoutRepeatingInput() throws Exception {
        JSONObject command=Jsons.object("id","retry","action","left","timestamp",Instant.now().minusSeconds(2).toString());
        send(command.toString(),false);
        command.put("timestamp",Instant.now().toString());
        assertTrue(send(command.toString(),false).getBoolean("duplicate"));
        assertEquals(1,executed.get());
    }
    @Test public void reusedIdWithDifferentActionIsRejected() throws Exception {
        send("{\"id\":\"one\",\"action\":\"left\"}",false);
        JSONObject ack=send("{\"id\":\"one\",\"action\":\"right\"}",false);
        assertEquals("id_conflict",ack.getJSONObject("error").getString("code")); assertEquals(1,executed.get());
    }
    @Test public void retainedCommandsNeverExecute() throws Exception {
        JSONObject ack=send("{\"id\":\"one\",\"action\":\"power_on\"}",true);
        assertEquals("retained_command",ack.getJSONObject("error").getString("code")); assertEquals(0,executed.get());
    }
    @Test public void expiredAndFutureCommandsNeverExecute() throws Exception {
        JSONObject old=Jsons.object("id","old","action","left","timestamp",Instant.now().minusSeconds(600).toString());
        assertEquals("expired_command",send(old.toString(),false).getJSONObject("error").getString("code"));
        JSONObject future=Jsons.object("id","future","action","left","timestamp",Instant.now().plusSeconds(600).toString());
        assertEquals("invalid_timestamp",send(future.toString(),false).getJSONObject("error").getString("code"));
        assertEquals(0,executed.get());
    }
    @Test public void malformedJsonAndMissingIdAreAcknowledged() throws Exception {
        assertFalse(send("{",false).getBoolean("ok"));
        assertFalse(send("{\"action\":\"left\"}",false).getBoolean("ok"));
        assertFalse(send("{\"id\":\"a\",\"action\":\"left\"} trailing",false).getBoolean("ok"));
        assertEquals(0,executed.get()); assertEquals(3,journal.pending().size());
    }
    @Test public void interruptedExecutionIsNeverRepeated() throws Exception {
        JSONObject command=Jsons.object("id","interrupted","action","left");
        journal.begin("interrupted",CommandProcessor.contentHash(command),"left",Jsons.now());
        CommandJournal recoveredJournal=new CommandJournal(file,16);
        CommandProcessor recovered=new CommandProcessor(recoveredJournal,c -> { executed.incrementAndGet(); return new JSONObject(); },120);
        JSONObject ack=recovered.handle(command.toString().getBytes(StandardCharsets.UTF_8),false,Jsons.now());
        assertEquals("execution_interrupted",ack.getJSONObject("error").getString("code"));
        assertTrue(ack.getBoolean("duplicate")); assertEquals(0,executed.get());
    }
    @Test public void outboxOnlyRemovesConfirmedPublications() throws Exception {
        send("{\"id\":\"a\",\"action\":\"left\"}",false);
        String token=journal.pending().get(0).getString("token");
        assertEquals(1,new CommandJournal(file,16).pending().size());
        journal.delivered(token);
        assertEquals(0,new CommandJournal(file,16).pending().size());
        assertNotNull(journal.find("a"));
    }
    @Test public void apiAndMqttShareIdHistoryWithoutFillingMqttOutbox() throws Exception {
        byte[] command="{\"id\":\"shared\",\"action\":\"left\"}".getBytes(StandardCharsets.UTF_8);
        assertTrue(processor.handle(command,false,Jsons.now(),false).getBoolean("ok"));
        assertEquals(0,journal.pending().size());
        assertTrue(processor.handle(command,false,Jsons.now()).getBoolean("duplicate"));
        assertEquals(1,executed.get());assertEquals(1,journal.pending().size());
    }
    @Test public void interruptedApiExecutionRecoversWithoutCreatingAMqttAck() throws Exception {
        JSONObject command=Jsons.object("id","api-interrupted","action","left");
        journal.begin("api-interrupted",CommandProcessor.contentHash(command),"left",Jsons.now(),false);
        CommandJournal recovered=new CommandJournal(file,16);
        assertEquals(0,recovered.pending().size());
        assertEquals("execution_interrupted",recovered.find("api-interrupted").getJSONObject("ack").getJSONObject("error").getString("code"));
    }
    @Test public void corruptJournalFailsClosed() throws Exception {
        java.nio.file.Files.write(file.toPath(),"broken".getBytes(StandardCharsets.UTF_8));
        try { new CommandJournal(file,16); fail("Expected journal error"); } catch(JSONException expected) {}
    }
}
