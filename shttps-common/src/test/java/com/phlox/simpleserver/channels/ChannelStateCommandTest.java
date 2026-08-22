package com.phlox.simpleserver.channels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * The five STATE commands as pure operations on a document - no channel, no lock, no socket. What
 * this covers is what a client can get wrong and what the document looks like afterwards; the
 * numbering, the fan-out and the acknowledgement are {@code ChannelsIntegrationTest}'s business.
 */
public class ChannelStateCommandTest {
    /** The sender tag only reads the participant's identity, so a socket is beside the point here. */
    private static final Participant ALICE = new Participant("p_0000dead", "alice", null, 1L, false, 65536);

    private static JSONObject command(String command, String path) {
        return new JSONObject().put("command", command).put("path", path);
    }

    private static JSONObject apply(JSONObject document, JSONObject envelope) throws Exception {
        ChannelStateCommand.parse(envelope).apply(document);
        return document;
    }

    @Test
    public void setWritesAValueAndCreatesTheLevelsAboveIt() throws Exception {
        JSONObject document = new JSONObject();

        apply(document, command(ChannelStateCommand.SET, "players/bob")
                .put("value", new JSONObject().put("score", 0)));

        assertEquals(0, document.getJSONObject("players").getJSONObject("bob").getInt("score"));
    }

    @Test
    public void mergeAssignsShallowlyOverWhatIsThere() throws Exception {
        JSONObject document = new JSONObject(
                "{\"players\":{\"alice\":{\"score\":3,\"colour\":\"red\"}}}");

        apply(document, command(ChannelStateCommand.MERGE, "players/alice")
                .put("value", new JSONObject().put("score", 4).put("ready", true)));

        JSONObject alice = document.getJSONObject("players").getJSONObject("alice");
        assertEquals(4, alice.getInt("score"));
        assertTrue(alice.getBoolean("ready"));
        assertEquals("red", alice.getString("colour"), "a key the merge did not mention stays");
    }

    @Test
    public void mergeIsTheOneCommandTheRootAccepts() throws Exception {
        JSONObject document = new JSONObject("{\"round\":1}");

        apply(document, new JSONObject().put("command", ChannelStateCommand.MERGE)
                .put("value", new JSONObject().put("round", 2)));
        assertEquals(2, document.getInt("round"));

        //a set of the root would be a whole-document replace, which the design document drops from
        //the protocol; the other three have no meaning there at all
        for (String op : new String[]{ChannelStateCommand.SET, ChannelStateCommand.DELETE,
                ChannelStateCommand.INCREMENT, ChannelStateCommand.PUSH}) {
            assertThrows(ChannelCommandException.class, () -> ChannelStateCommand.parse(
                    new JSONObject().put("command", op).put("value", 1).put("by", 1)),
                    op + " must not accept the document root");
        }
    }

    @Test
    public void mergeRefusesAnythingButAnObject() {
        assertThrows(ChannelCommandException.class, () -> ChannelStateCommand.parse(
                command(ChannelStateCommand.MERGE, "players/alice").put("value", 4)));
        assertThrows(ChannelCommandException.class, () -> ChannelStateCommand.parse(
                command(ChannelStateCommand.MERGE, "players/alice")));

        assertThrows(ChannelCommandException.class, () -> apply(
                new JSONObject("{\"players\":{\"alice\":7}}"),
                command(ChannelStateCommand.MERGE, "players/alice")
                        .put("value", new JSONObject().put("score", 4))));
    }

    @Test
    public void deleteRemovesAKeyAndAnAbsentOneIsStillASuccess() throws Exception {
        JSONObject document = new JSONObject("{\"players\":{\"bob\":{\"score\":0}}}");

        apply(document, command(ChannelStateCommand.DELETE, "players/bob"));
        assertFalse(document.getJSONObject("players").has("bob"));

        //deleting it again asks for a document that is already the case: a no-op, not an error
        apply(document, command(ChannelStateCommand.DELETE, "players/bob"));
        apply(document, command(ChannelStateCommand.DELETE, "rooms/main/nobody"));
        assertFalse(document.has("rooms"), "a no-op delete must not create the levels of its path");
    }

    @Test
    public void incrementAddsAndKeepsWholeNumbersWhole() throws Exception {
        JSONObject document = new JSONObject("{\"players\":{\"alice\":{\"score\":3}}}");

        apply(document, command(ChannelStateCommand.INCREMENT, "players/alice/score").put("by", 1));

        Object score = document.getJSONObject("players").getJSONObject("alice").get("score");
        assertEquals(4L, ((Number) score).longValue());
        assertEquals("4", String.valueOf(score), "3 + 1 has to stay 4 on the wire, not become 4.0");

        //a counter nobody has touched yet starts at zero, so a client need not create one first
        apply(document, command(ChannelStateCommand.INCREMENT, "players/bob/score").put("by", 2));
        assertEquals(2, document.getJSONObject("players").getJSONObject("bob").getInt("score"));

        apply(document, command(ChannelStateCommand.INCREMENT, "players/bob/score").put("by", 0.5));
        assertEquals(2.5, document.getJSONObject("players").getJSONObject("bob").getDouble("score"));
    }

    @Test
    public void incrementNeedsANumberOnBothSides() {
        assertThrows(ChannelCommandException.class, () -> apply(
                new JSONObject("{\"players\":{\"alice\":{\"score\":\"three\"}}}"),
                command(ChannelStateCommand.INCREMENT, "players/alice/score").put("by", 1)));
        assertThrows(ChannelCommandException.class, () -> ChannelStateCommand.parse(
                command(ChannelStateCommand.INCREMENT, "players/alice/score").put("by", "one")));
        assertThrows(ChannelCommandException.class, () -> ChannelStateCommand.parse(
                command(ChannelStateCommand.INCREMENT, "players/alice/score")));
    }

    @Test
    public void pushAppendsAndCreatesTheArrayItNeeds() throws Exception {
        JSONObject document = new JSONObject();

        apply(document, command(ChannelStateCommand.PUSH, "log")
                .put("value", new JSONObject().put("text", "alice joined")));
        apply(document, command(ChannelStateCommand.PUSH, "log")
                .put("value", new JSONObject().put("text", "bob joined")));

        assertEquals(2, document.getJSONArray("log").length());
        assertEquals("alice joined", document.getJSONArray("log").getJSONObject(0).getString("text"));
        assertEquals("bob joined", document.getJSONArray("log").getJSONObject(1).getString("text"));
    }

    @Test
    public void pushRefusesAPathThatHoldsSomethingElse() {
        assertThrows(ChannelCommandException.class, () -> apply(
                new JSONObject("{\"log\":\"not an array\"}"),
                command(ChannelStateCommand.PUSH, "log").put("value", 1)));
    }

    @Test
    public void theEventRestatesTheCommand() throws Exception {
        JSONObject value = new JSONObject().put("score", 4);
        JSONObject event = ChannelStateCommand
                .parse(command(ChannelStateCommand.MERGE, "players/alice").put("value", value))
                .toEvent(42, ALICE);

        assertEquals("patch", event.getString("type"));
        assertEquals(ChannelStateCommand.MERGE, event.getString("op"));
        assertEquals("players/alice", event.getString("path"));
        assertEquals(4, event.getJSONObject("value").getInt("score"));
        assertEquals(42, event.getLong("seq"));
        assertEquals("p_0000dead", event.getJSONObject("by").getString("participantId"));
        assertEquals("alice", event.getJSONObject("by").getString("identity"));
    }

    @Test
    public void theEventCarriesTheIncrementAmountAsItsValue() throws Exception {
        //§8 writes the amount as "by" on the way in, but "by" on the way out is the sender tag
        JSONObject event = ChannelStateCommand
                .parse(command(ChannelStateCommand.INCREMENT, "players/alice/score").put("by", 2))
                .toEvent(43, ALICE);

        assertEquals(2, event.getInt("value"));
        assertEquals("p_0000dead", event.getJSONObject("by").getString("participantId"));
    }

    @Test
    public void aDeleteEventCarriesNoValue() throws Exception {
        JSONObject event = ChannelStateCommand
                .parse(command(ChannelStateCommand.DELETE, "players/bob")).toEvent(44, ALICE);

        assertFalse(event.has("value"));
        assertEquals("players/bob", event.getString("path"));
    }

    @Test
    public void knowsWhichCommandsAreItsOwn() {
        assertTrue(ChannelStateCommand.isStateCommand("set"));
        assertTrue(ChannelStateCommand.isStateCommand("push"));
        assertFalse(ChannelStateCommand.isStateCommand("send"));
        assertFalse(ChannelStateCommand.isStateCommand("replace"));
        assertFalse(ChannelStateCommand.isStateCommand(null));
    }
}
