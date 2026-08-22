package com.phlox.simpleserver.channels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.phlox.server.SimpleHttpServer;
import com.phlox.server.handlers.router.Router;
import com.phlox.server.handlers.router.middleware.Middleware;
import com.phlox.server.request.Request;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.ConfigBasedUserStore;
import com.phlox.simpleserver.auth.DummyAuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.auth.UserStore;
import com.phlox.simpleserver.auth.basic.BasicAuthManager;
import com.phlox.simpleserver.auth.basic.BasicAuthMiddleware;
import com.phlox.simpleserver.database.TestDBEnvironment;
import com.phlox.simpleserver.handlers.channels.ChannelWebSocketHandler;
import com.phlox.simpleserver.handlers.channels.ChannelsCollectionRequestHandler;
import com.phlox.simpleserver.handlers.channels.ChannelsPathRequestHandler;
import com.phlox.simpleserver.utils.Utils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * End to end checks of a predefined ECHO channel over a real socket: the message fan-out itself,
 * and the two refusals that have to happen <b>before</b> the WebSocket upgrade rather than on an
 * already-open connection.
 * <p>
 * Built the way {@code WebSocketServerIntegrationTest} in {@code server-lib} is - a real
 * {@link SimpleHttpServer} on a loopback port and a hand-rolled client that masks its own frames -
 * because that is what lets a test look at the HTTP status line the server answers a rejected
 * handshake with.
 */
@Timeout(30)
public class ChannelsIntegrationTest {
    private static final String CHANNEL = "lobby";
    private static final String PASSWORD = "let-me-in";
    private static final String USER_PASSWORD = "s3cret";

    private SimpleHttpServer server;
    private int port;
    private final TestDBEnvironment.Config config = new TestDBEnvironment.Config();

    @AfterEach
    public void stopServer() {
        if (server != null) {
            server.stopListen();
            server = null;
        }
    }

    @Test
    public void deliversAMessageToTheOtherParticipantsButNotToTheSender() throws Exception {
        config.setAuthMode(SHTTPSConfig.AuthMode.NONE);
        start(Collections.singletonList(echoChannel()), new DummyAuthManager(),
                Collections.emptyList());

        try (WsTestClient alice = connect(); WsTestClient bob = connect()) {
            alice.handshake(connectPath(CHANNEL));
            bob.handshake(connectPath(CHANNEL));
            //authorization is off, so both connections may list participants and are greeted with
            //the list before anything else
            alice.readParticipantsSnapshot();
            bob.readParticipantsSnapshot();

            alice.send(1, new JSONObject().put("text", "hi"));

            JSONObject ack = alice.readJson();
            assertEquals(1, ack.getInt("id"));
            assertTrue(ack.getBoolean("ok"));
            assertEquals(1, ack.getLong("seq"), "the first event of a fresh channel is seq 1");
            assertFalse(ack.has("type"), "the sender gets an acknowledgement, not the event");

            JSONObject event = bob.readJson();
            assertEquals("message", event.getString("type"));
            assertEquals("hi", event.getJSONObject("payload").getString("text"));
            assertEquals(1, event.getLong("seq"));
            assertEquals("ws", event.getString("source"));
            assertNotNull(event.getJSONObject("from").getString("participantId"));
            //authorization is off, so nobody has an identity to publish
            assertTrue(event.getJSONObject("from").isNull("identity"));

            //the sender must not have received its own message: if it had, this frame - the event
            //for bob's message - would be behind it in alice's stream
            bob.send(2, new JSONObject().put("text", "hello back"));
            JSONObject next = alice.readJson();
            assertEquals("message", next.getString("type"));
            assertEquals("hello back", next.getJSONObject("payload").getString("text"),
                    "alice received her own message back although echoToSelf was not requested");
            assertEquals(2, next.getLong("seq"));
        }
    }

    @Test
    public void echoToSelfAlsoDeliversTheEventToTheSender() throws Exception {
        config.setAuthMode(SHTTPSConfig.AuthMode.NONE);
        start(Collections.singletonList(echoChannel()), new DummyAuthManager(),
                Collections.emptyList());

        try (WsTestClient alice = connect(); WsTestClient bob = connect()) {
            alice.handshake(connectPath(CHANNEL) + "?echoToSelf=true");
            bob.handshake(connectPath(CHANNEL));
            alice.readParticipantsSnapshot();
            bob.readParticipantsSnapshot();

            alice.send(1, new JSONObject().put("text", "hi"));

            assertTrue(alice.readJson().getBoolean("ok"));
            JSONObject ownEvent = alice.readJson();
            assertEquals("message", ownEvent.getString("type"));
            assertEquals("hi", ownEvent.getJSONObject("payload").getString("text"));
            //the same event still reaches everybody else exactly once
            assertEquals("hi", bob.readJson().getJSONObject("payload").getString("text"));
        }
    }

    @Test
    public void aWrongChannelPasswordIsRefusedBeforeTheUpgrade() throws Exception {
        config.setAuthMode(SHTTPSConfig.AuthMode.NONE);
        ChannelDefinition definition = new ChannelDefinition(CHANNEL, Channel.Mode.ECHO, null, null,
                false, true, true, ChannelDefinition.hashChannelPassword(PASSWORD));
        start(Collections.singletonList(definition), new DummyAuthManager(), Collections.emptyList());

        try (WsTestClient client = connect()) {
            Map<String, String> response = client.handshakeRaw(connectPath(CHANNEL) + "?password=wrong");

            assertEquals("HTTP/1.1 403 Forbidden", response.get(WsTestClient.STATUS_LINE),
                    "a wrong password must be an ordinary HTTP refusal, not a socket that opens and closes");
            assertNull(response.get("sec-websocket-accept"), "the handshake must not have been accepted");
            assertNull(response.get("upgrade"));
        }

        //a missing password is refused the same way, and the right one still gets in
        try (WsTestClient client = connect()) {
            assertEquals("HTTP/1.1 403 Forbidden",
                    client.handshakeRaw(connectPath(CHANNEL)).get(WsTestClient.STATUS_LINE));
        }
        try (WsTestClient client = connect()) {
            client.handshake(connectPath(CHANNEL) + "?password=" + PASSWORD);
        }
    }

    @Test
    public void aChannelClosedToGuestsRefusesAnUnauthenticatedConnection() throws Exception {
        config.setAuthMode(SHTTPSConfig.AuthMode.BASIC_AUTH);
        //a guest user exists, so the auth middleware lets the request through as the guest and the
        //refusal is the channel's own rather than a plain 401
        UserStore userStore = userStoreWith(
                guest(),
                userWith("alice", User.ChannelRights.CONNECT, User.ChannelRights.POST));
        AuthManager authManager = new BasicAuthManager(userStore);

        ChannelDefinition definition = new ChannelDefinition(CHANNEL, Channel.Mode.ECHO, null, null,
                false, true, /*guestsAllowed*/ false, null);
        start(Collections.singletonList(definition), authManager,
                Collections.singletonList(new BasicAuthMiddleware(authManager)));

        try (WsTestClient client = connect()) {
            Map<String, String> response = client.handshakeRaw(connectPath(CHANNEL));

            assertEquals("HTTP/1.1 403 Forbidden", response.get(WsTestClient.STATUS_LINE));
            assertNull(response.get("sec-websocket-accept"));
        }

        //a named user is let into the very same channel
        try (WsTestClient client = connect()) {
            client.handshake(connectPath(CHANNEL), basicAuth("alice"));
        }
    }

    @Test
    public void connectWithoutThePostRightIsReadOnly() throws Exception {
        config.setAuthMode(SHTTPSConfig.AuthMode.BASIC_AUTH);
        UserStore userStore = userStoreWith(
                userWith("listener", User.ChannelRights.CONNECT),
                userWith("speaker", User.ChannelRights.CONNECT, User.ChannelRights.POST));
        AuthManager authManager = new BasicAuthManager(userStore);
        start(Collections.singletonList(echoChannel()), authManager,
                Collections.singletonList(new BasicAuthMiddleware(authManager)));

        try (WsTestClient listener = connect(); WsTestClient speaker = connect()) {
            listener.handshake(connectPath(CHANNEL), basicAuth("listener"));
            speaker.handshake(connectPath(CHANNEL), basicAuth("speaker"));

            listener.send(1, new JSONObject().put("text", "may I?"));
            JSONObject error = listener.readJson();
            assertFalse(error.getBoolean("ok"));
            assertEquals("FORBIDDEN", error.getJSONObject("error").getString("kind"));

            //refused, but still connected and still listening
            speaker.send(2, new JSONObject().put("text", "you may not"));
            assertEquals("you may not",
                    listener.readJson().getJSONObject("payload").getString("text"));
        }
    }

    @Test
    public void listsChannelsAndTheirParticipantsOverPlainHttp() throws Exception {
        config.setAuthMode(SHTTPSConfig.AuthMode.NONE);
        start(Collections.singletonList(echoChannel()), new DummyAuthManager(),
                Collections.emptyList());

        try (WsTestClient alice = connect(); WsTestClient bob = connect(); WsTestClient rest = connect()) {
            alice.handshake(connectPath(CHANNEL));
            bob.handshake(connectPath(CHANNEL));

            HttpAnswer list = rest.get(ChannelsCollectionRequestHandler.PATH, Collections.emptyMap());
            assertEquals("HTTP/1.1 200 Ok", list.statusLine);
            JSONArray channels = new JSONObject(list.body).getJSONArray("channels");
            assertEquals(1, channels.length());
            JSONObject lobby = channels.getJSONObject(0);
            assertEquals(CHANNEL, lobby.getString("id"));
            assertEquals("echo", lobby.getString("mode"));
            assertTrue(lobby.getBoolean("persistent"));
            assertFalse(lobby.getBoolean("deletable"));
            assertFalse(lobby.getBoolean("passwordProtected"));
            assertEquals(2, lobby.getInt("participants"));
            assertFalse(lobby.has("passwordHash"), "the channel secret must not be listed");

            HttpAnswer participants = rest.get(
                    ChannelsPathRequestHandler.PATH_PREFIX + CHANNEL + "/" +
                            ChannelsPathRequestHandler.ACTION_PARTICIPANTS, Collections.emptyMap());
            assertEquals("HTTP/1.1 200 Ok", participants.statusLine);
            JSONObject answer = new JSONObject(participants.body);
            assertEquals(CHANNEL, answer.getString("channel"));
            JSONArray connected = answer.getJSONArray("participants");
            assertEquals(2, connected.length());
            for (int i = 0; i < connected.length(); i++) {
                JSONObject participant = connected.getJSONObject(i);
                assertNotNull(participant.getString("participantId"));
                assertTrue(participant.getLong("joinedAt") > 0);
                //authorization is off, so these connections are anonymous and carry no identity
                assertFalse(participant.has("identity"));
            }
        }
    }

    @Test
    public void theListingRightsAreEnforcedSeparately() throws Exception {
        config.setAuthMode(SHTTPSConfig.AuthMode.BASIC_AUTH);
        UserStore userStore = userStoreWith(
                userWith("nosey", User.ChannelRights.CONNECT, User.ChannelRights.LIST_CHANNELS),
                userWith("quiet", User.ChannelRights.CONNECT));
        AuthManager authManager = new BasicAuthManager(userStore);
        start(Collections.singletonList(echoChannel()), authManager,
                Collections.singletonList(new BasicAuthMiddleware(authManager)));

        String participantsPath = ChannelsPathRequestHandler.PATH_PREFIX + CHANNEL + "/" +
                ChannelsPathRequestHandler.ACTION_PARTICIPANTS;
        try (WsTestClient client = connect()) {
            assertEquals("HTTP/1.1 200 Ok",
                    client.get(ChannelsCollectionRequestHandler.PATH, basicAuth("nosey")).statusLine);
            //LIST_CHANNELS does not carry LIST_PARTICIPANTS with it
            assertEquals("HTTP/1.1 403 Forbidden",
                    client.get(participantsPath, basicAuth("nosey")).statusLine);
        }
        try (WsTestClient client = connect()) {
            assertEquals("HTTP/1.1 403 Forbidden",
                    client.get(ChannelsCollectionRequestHandler.PATH, basicAuth("quiet")).statusLine);
            //CONNECT alone is still enough to read one channel's metadata
            assertEquals("HTTP/1.1 200 Ok",
                    client.get(ChannelsPathRequestHandler.PATH_PREFIX + CHANNEL, basicAuth("quiet")).statusLine);
        }
    }

    @Test
    public void aStateChannelSendsTheDocumentOnConnectAndEachCommandAsAPatch() throws Exception {
        config.setAuthMode(SHTTPSConfig.AuthMode.NONE);
        start(Collections.singletonList(stateChannel(new JSONObject().put("round", 1))),
                new DummyAuthManager(), Collections.emptyList());

        try (WsTestClient alice = connect(); WsTestClient bob = connect()) {
            alice.handshake(connectPath(CHANNEL));
            bob.handshake(connectPath(CHANNEL));

            //the first frame of a STATE connection is the document as it stands, unasked for, and
            //the participant list follows it
            for (WsTestClient client : List.of(alice, bob)) {
                JSONObject snapshot = client.readJson();
                assertEquals("state", snapshot.getString("type"));
                assertEquals(0, snapshot.getLong("seq"), "nothing has happened in this channel yet");
                assertEquals(1, snapshot.getJSONObject("state").getInt("round"));
                client.readParticipantsSnapshot();
            }

            JSONObject set = exchange(alice, bob, 1, 1, command("set", "players/bob")
                    .put("value", new JSONObject().put("score", 0)));
            assertEquals("set", set.getString("op"));
            assertEquals("players/bob", set.getString("path"));
            assertEquals(0, set.getJSONObject("value").getInt("score"));
            assertNotNull(set.getJSONObject("by").getString("participantId"));
            //authorization is off, so nobody has an identity to publish
            assertTrue(set.getJSONObject("by").isNull("identity"));

            JSONObject merge = exchange(alice, bob, 2, 2, command("merge", "players/bob")
                    .put("value", new JSONObject().put("score", 3).put("ready", true)));
            assertEquals("merge", merge.getString("op"));
            assertEquals(3, merge.getJSONObject("value").getInt("score"));
            assertTrue(merge.getJSONObject("value").getBoolean("ready"));

            JSONObject increment = exchange(alice, bob, 3, 3,
                    command("increment", "players/bob/score").put("by", 2));
            assertEquals("increment", increment.getString("op"));
            //§8 writes the amount as "by" on the way in; on the way out "by" is the sender
            assertEquals(2, increment.getInt("value"));
            assertEquals("players/bob/score", increment.getString("path"));

            JSONObject push = exchange(alice, bob, 4, 4, command("push", "log")
                    .put("value", new JSONObject().put("text", "bob is ready")));
            assertEquals("push", push.getString("op"));
            assertEquals("bob is ready", push.getJSONObject("value").getString("text"));

            JSONObject delete = exchange(alice, bob, 5, 5, command("delete", "players/bob/ready"));
            assertEquals("delete", delete.getString("op"));
            assertEquals("players/bob/ready", delete.getString("path"));
            assertFalse(delete.has("value"), "a delete has no value to carry");

            //a command belonging to the other mode is refused without ending the connection
            alice.send(6, new JSONObject().put("text", "hi"));
            JSONObject refused = alice.readJson();
            assertFalse(refused.getBoolean("ok"));
            assertEquals("BAD_REQUEST", refused.getJSONObject("error").getString("kind"));

            //alice must not have received any of her own patches: if she had, they would be ahead
            //of this one in her stream
            bob.sendEnvelope(command("increment", "players/bob/score").put("by", 10).put("id", 7));
            JSONObject fromBob = alice.readJson();
            assertEquals("patch", fromBob.getString("type"),
                    "alice received her own patches although echoToSelf was not requested");
            assertEquals(10, fromBob.getInt("value"));
            assertEquals(6, fromBob.getLong("seq"), "every applied command numbers exactly once");

            //a latecomer's snapshot is the sum of everything that happened before it
            try (WsTestClient carol = connect()) {
                carol.handshake(connectPath(CHANNEL));
                JSONObject snapshot = carol.readJson();
                assertEquals("state", snapshot.getString("type"));
                assertEquals(6, snapshot.getLong("seq"));
                JSONObject document = snapshot.getJSONObject("state");
                JSONObject bobsEntry = document.getJSONObject("players").getJSONObject("bob");
                assertEquals(15, bobsEntry.getInt("score"), "0, merged to 3, then +2 and +10");
                assertFalse(bobsEntry.has("ready"), "the deleted key is gone from the document");
                assertEquals("bob is ready",
                        document.getJSONArray("log").getJSONObject(0).getString("text"));
                assertEquals(1, document.getInt("round"), "the initial state is still under it all");
            }
        }
    }

    @Test
    public void aStateChannelIsReadableWithoutThePostRight() throws Exception {
        config.setAuthMode(SHTTPSConfig.AuthMode.BASIC_AUTH);
        UserStore userStore = userStoreWith(
                userWith("listener", User.ChannelRights.CONNECT),
                userWith("speaker", User.ChannelRights.CONNECT, User.ChannelRights.POST));
        AuthManager authManager = new BasicAuthManager(userStore);
        start(Collections.singletonList(stateChannel(new JSONObject().put("round", 1))),
                authManager, Collections.singletonList(new BasicAuthMiddleware(authManager)));

        try (WsTestClient listener = connect(); WsTestClient speaker = connect()) {
            listener.handshake(connectPath(CHANNEL), basicAuth("listener"));
            speaker.handshake(connectPath(CHANNEL), basicAuth("speaker"));

            //CONNECT alone still gets the whole document
            assertEquals(1, listener.readJson().getJSONObject("state").getInt("round"));
            assertEquals("state", speaker.readJson().getString("type"));

            listener.sendEnvelope(command("set", "round").put("value", 2).put("id", 1));
            JSONObject error = listener.readJson();
            assertFalse(error.getBoolean("ok"));
            assertEquals("FORBIDDEN", error.getJSONObject("error").getString("kind"));

            //refused, but still connected and still watching the document change
            speaker.sendEnvelope(command("increment", "round").put("by", 1).put("id", 2));
            JSONObject patch = listener.readJson();
            assertEquals("increment", patch.getString("op"));
            assertEquals(1, patch.getLong("seq"), "the refused command must not have been numbered");
            assertEquals("speaker", patch.getJSONObject("by").getString("identity"));
        }
    }

    /**
     * The reason applying a command and numbering it happen under one lock: two participants
     * raising the same counter must end up with both increments, and with one event number each.
     */
    @Test
    public void concurrentIncrementsOfOneCounterAreAllApplied() throws Exception {
        config.setAuthMode(SHTTPSConfig.AuthMode.NONE);
        //this test deliberately sends as fast as it can, which is what the default limit of 20 a
        //second exists to stop; the interleaving it is about is not the limiter's subject, so the
        //limiter is turned off rather than tiptoed around
        config.setChannelMessageRateLimitPerSecond(0);
        start(Collections.singletonList(stateChannel(new JSONObject().put("hits", 0))),
                new DummyAuthManager(), Collections.emptyList());
        int perParticipant = 25;

        try (WsTestClient alice = connect(); WsTestClient bob = connect()) {
            alice.handshake(connectPath(CHANNEL));
            bob.handshake(connectPath(CHANNEL));
            alice.readJson();//the state snapshots, and the participant lists behind them
            alice.readParticipantsSnapshot();
            bob.readJson();
            bob.readParticipantsSnapshot();

            //neither connection waits for the other before sending the next command, and the server
            //runs each of them on its own thread, so the applying really does interleave
            for (int i = 0; i < perParticipant; i++) {
                alice.sendEnvelope(command("increment", "hits").put("by", 1).put("id", i));
                bob.sendEnvelope(command("increment", "hits").put("by", 1).put("id", i));
            }
            awaitAcknowledgements(alice, perParticipant);
            awaitAcknowledgements(bob, perParticipant);

            try (WsTestClient carol = connect()) {
                carol.handshake(connectPath(CHANNEL));
                JSONObject snapshot = carol.readJson();
                assertEquals(2 * perParticipant, snapshot.getJSONObject("state").getInt("hits"),
                        "an increment was lost between reading the counter and writing it back");
                assertEquals(2 * perParticipant, snapshot.getLong("seq"),
                        "every applied command has to have taken exactly one event number");
            }
        }
    }

    /**
     * The regression a user found: with two people talking at once, {@code message} events reached
     * a recipient complete but shuffled, because the numbering happened under the channel's lock
     * while the writing happened after it, on each sender's own thread. A client following the
     * documented advice - "a gap in seq means you missed something" - then cried wolf every time
     * the channel got busy.
     */
    @Test
    public void concurrentMessagesArriveInSeqOrder() throws Exception {
        config.setAuthMode(SHTTPSConfig.AuthMode.NONE);
        //the point here is the interleaving, not the limiter
        config.setChannelMessageRateLimitPerSecond(0);
        start(Collections.singletonList(echoChannel()), new DummyAuthManager(),
                Collections.emptyList());
        int perSender = 40;

        try (WsTestClient alice = connect(); WsTestClient bob = connect();
             WsTestClient watcher = connect()) {
            alice.handshake(connectPath(CHANNEL));
            alice.readParticipantsSnapshot();
            bob.handshake(connectPath(CHANNEL));
            bob.readParticipantsSnapshot();
            watcher.handshake(connectPath(CHANNEL));
            watcher.readParticipantsSnapshot();

            //neither sender waits for the other, and the server handles each on its own thread, so
            //the two really do reach the channel at the same time
            for (int i = 0; i < perSender; i++) {
                alice.send(i, new JSONObject().put("from", "alice").put("n", i));
                bob.send(i, new JSONObject().put("from", "bob").put("n", i));
            }

            for (int expected = 1; expected <= 2 * perSender; expected++) {
                JSONObject event = watcher.readJson();
                assertEquals("message", event.getString("type"));
                assertEquals(expected, event.getLong("seq"),
                        "messages reached a recipient out of the order they were numbered in");
            }
        }
    }

    /**
     * The same race in a STATE channel, where it is not a false alarm but a wrong document: two
     * {@code set}s of one path delivered in the other order leave a client holding the value the
     * server threw away, for good, with nothing to tell it so.
     */
    @Test
    public void concurrentSetsOfOnePathArriveInSeqOrder() throws Exception {
        config.setAuthMode(SHTTPSConfig.AuthMode.NONE);
        config.setChannelMessageRateLimitPerSecond(0);
        start(Collections.singletonList(stateChannel(new JSONObject().put("v", 0))),
                new DummyAuthManager(), Collections.emptyList());
        int perSender = 40;

        try (WsTestClient alice = connect(); WsTestClient bob = connect();
             WsTestClient watcher = connect()) {
            for (WsTestClient client : List.of(alice, bob, watcher)) {
                client.handshake(connectPath(CHANNEL));
                client.readJson();//the state snapshot
                client.readParticipantsSnapshot();
            }

            for (int i = 0; i < perSender; i++) {
                alice.sendEnvelope(command("set", "v").put("value", "alice-" + i).put("id", i));
                bob.sendEnvelope(command("set", "v").put("value", "bob-" + i).put("id", i));
            }

            //applied in arrival order, exactly as a client written from the documentation would
            String applied = null;
            for (int expected = 1; expected <= 2 * perSender; expected++) {
                JSONObject patch = watcher.readJson();
                assertEquals("patch", patch.getString("type"));
                assertEquals(expected, patch.getLong("seq"),
                        "patches reached a participant out of the order they were applied in");
                applied = patch.getString("value");
            }

            //and the document that leaves the client with has to be the server's own
            try (WsTestClient carol = connect()) {
                carol.handshake(connectPath(CHANNEL));
                JSONObject snapshot = carol.readJson();
                assertEquals(applied, snapshot.getJSONObject("state").getString("v"),
                        "a client applying patches in arrival order ended up disagreeing with the server");
            }
        }
    }

    /**
     * The snapshot a newcomer is greeted with is queued under the same lock that admits it, so a
     * change numbered immediately afterwards cannot reach that connection ahead of the document it
     * changes. Sent after the lock, as it once was, it could.
     */
    @Test
    public void theSnapshotArrivesBeforeThePatchesThatFollowIt() throws Exception {
        config.setAuthMode(SHTTPSConfig.AuthMode.NONE);
        config.setChannelMessageRateLimitPerSecond(0);
        start(Collections.singletonList(stateChannel(new JSONObject().put("v", 0))),
                new DummyAuthManager(), Collections.emptyList());
        int changes = 60;

        try (WsTestClient writer = connect()) {
            writer.handshake(connectPath(CHANNEL));
            writer.readJson();
            writer.readParticipantsSnapshot();

            //kept busy while the newcomer is admitted, so admission really does land in the middle
            //of a stream of changes rather than in a quiet channel
            Thread mutations = new Thread(() -> {
                try {
                    for (int i = 0; i < changes; i++) {
                        writer.sendEnvelope(command("set", "v").put("value", i).put("id", i));
                    }
                } catch (IOException ignored) {
                    //the connection is closed by the test when it finishes
                }
            });
            mutations.start();

            try (WsTestClient latecomer = connect()) {
                latecomer.handshake(connectPath(CHANNEL));
                JSONObject snapshot = latecomer.readJson();
                assertEquals("state", snapshot.getString("type"),
                        "the first thing a state participant hears has to be the document");
                long snapshotSeq = snapshot.getLong("seq");
                latecomer.readParticipantsSnapshot();

                mutations.join();
                //every change numbered after the snapshot, and none of the ones already in it
                long previous = snapshotSeq;
                while (previous < changes) {
                    JSONObject patch = latecomer.readJson();
                    assertEquals("patch", patch.getString("type"));
                    assertEquals(previous + 1, patch.getLong("seq"),
                            "a patch arrived out of order, or one already in the snapshot was repeated");
                    previous = patch.getLong("seq");
                }
            }
            mutations.join();
        }
    }

    /** Reads until that many answers have arrived, past the other participant's interleaved events. */
    private static void awaitAcknowledgements(WsTestClient client, int count) throws IOException {
        for (int seen = 0; seen < count; ) {
            if (client.readJson().has("ok")) {
                seen++;
            }
        }
    }

    private static JSONObject command(String command, String path) {
        return new JSONObject().put("command", command).put("path", path);
    }

    /**
     * Presence is off in the fixtures of this class: with authorization off every connection holds
     * every right, so join and leave events would otherwise land in the middle of the message and
     * patch streams these tests are about. They are {@code ChannelsPresenceTest}'s subject. The
     * participant list a connection is greeted with is <b>not</b> a per-channel setting, so the
     * tests below still consume it where they read frames.
     */
    private static ChannelDefinition echoChannel() {
        return new ChannelDefinition(CHANNEL, Channel.Mode.ECHO, null, null,
                false, /*notifyPresence*/ false, true, null);
    }

    private static ChannelDefinition stateChannel(JSONObject initialState) {
        return new ChannelDefinition(CHANNEL, Channel.Mode.STATE, initialState, null,
                false, /*notifyPresence*/ false, true, null);
    }

    /**
     * Sends one state command, checks that its sender gets a plain acknowledgement and nothing
     * else, and returns the patch event the other participant receives for it.
     */
    private JSONObject exchange(WsTestClient sender, WsTestClient other, int id, long expectedSeq,
                                JSONObject command) throws IOException {
        sender.sendEnvelope(command.put("id", id));

        JSONObject ack = sender.readJson();
        assertTrue(ack.optBoolean("ok"), "the command was refused: " + ack);
        assertEquals(id, ack.getInt("id"));
        assertEquals(expectedSeq, ack.getLong("seq"));
        assertFalse(ack.has("type"), "the sender gets an acknowledgement, not the event");
        assertFalse(ack.has("value"), "the acknowledgement must not repeat what was sent");

        JSONObject patch = other.readJson();
        assertEquals("patch", patch.getString("type"));
        assertEquals(expectedSeq, patch.getLong("seq"));
        return patch;
    }

    private String connectPath(String channelId) {
        return ChannelsPathRequestHandler.PATH_PREFIX + channelId + "/" +
                ChannelsPathRequestHandler.ACTION_CONNECT;
    }

    private void start(List<ChannelDefinition> definitions, AuthManager authManager,
                       List<Middleware> authMiddlewares) throws IOException {
        config.setChannelsEnabled(true);
        config.setPredefinedChannels(definitions);

        ChannelManager channelManager = new ChannelManager();
        channelManager.applyPredefinedChannels(config);

        ChannelWebSocketHandler webSocketHandler =
                new ChannelWebSocketHandler(channelManager, config, authManager);
        //keep the tests deterministic: no pings appearing in the middle of the expected frames
        webSocketHandler.options.idlePingIntervalMillis = 0;

        Router router = new Router(null, Collections.emptyList());
        router.addRoute(ChannelsCollectionRequestHandler.PATH,
                Set.of(Request.METHOD_GET, Request.METHOD_POST),
                new ChannelsCollectionRequestHandler(channelManager, config, authManager), authMiddlewares);
        router.addRouteByPathPrefix(ChannelsPathRequestHandler.PATH_PREFIX,
                new ChannelsPathRequestHandler(channelManager, config, authManager, webSocketHandler),
                authMiddlewares);

        ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        port = serverSocket.getLocalPort();
        server = new SimpleHttpServer(router, null);
        server.startListen(serverSocket);
    }

    private WsTestClient connect() throws IOException {
        return new WsTestClient(port);
    }

    private UserStore userStoreWith(User... users) {
        config.setUsers(new ArrayList<>(List.of(users)));
        return new ConfigBasedUserStore(config);
    }

    private static User userWith(String identity, User.ChannelRights... rights) {
        EnumSet<User.ChannelRights> channelRights = rights.length == 0 ?
                EnumSet.noneOf(User.ChannelRights.class) : EnumSet.copyOf(List.of(rights));
        return new User(identity, Utils.sha256(Utils.hashFNV1a32(USER_PASSWORD)), null,
                EnumSet.noneOf(User.FileSystemRights.class), EnumSet.noneOf(User.DBRights.class),
                null, 0L, null, null, EnumSet.noneOf(User.SystemRights.class), 0L, channelRights);
    }

    private static User guest() {
        return userWith(User.GUEST_IDENTITY, User.ChannelRights.CONNECT);
    }

    private static Map<String, String> basicAuth(String identity) {
        String credentials = identity + ":" + USER_PASSWORD;
        return Collections.singletonMap("Authorization", "Basic " +
                Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
    }
}
