package com.phlox.simpleserver.channels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * Dynamic channel management (§4): creating a channel over REST, deleting one, and the two limits
 * that decide how long a channel nobody asked to keep may live - {@code maxDynamicChannels} and
 * {@code channelIdleTimeoutMillis}.
 * <p>
 * Built like {@link ChannelsIntegrationTest}, a real server on a loopback port and the same
 * hand-rolled client, because half of what is checked here is the plain HTTP answer and the other
 * half is what the already-connected sockets see when their channel goes away.
 * <p>
 * The idle sweep is invoked directly rather than waited for: {@code SHTTPSApp} runs it on a timer,
 * but the collecting itself is {@link ChannelManager}'s, and calling it makes the tests exact
 * instead of merely patient.
 */
@Timeout(30)
public class ChannelsManagementTest {
    private static final String PREDEFINED = "lobby";
    private static final String CREATED = "room-1";
    private static final String USER_PASSWORD = "s3cret";
    /** Short, so the collection tests cost milliseconds; nothing clamps this setting. */
    private static final int IDLE_TIMEOUT_MS = 50;

    private SimpleHttpServer server;
    private int port;
    private ChannelManager channels;
    private final TestDBEnvironment.Config config = new TestDBEnvironment.Config();

    @AfterEach
    public void stopServer() {
        if (server != null) {
            server.stopListen();
            server = null;
        }
    }

    @Test
    public void creatingAChannelWithoutTheCreateRightIsRefused() throws Exception {
        config.setAuthMode(SHTTPSConfig.AuthMode.BASIC_AUTH);
        //switched on, so a refusal here can only be about the right the user is missing
        config.setAllowDynamicChannelCreation(true);
        AuthManager authManager = new BasicAuthManager(
                userStoreWith(userWith("listener", User.ChannelRights.CONNECT)));
        start(Collections.emptyList(), authManager,
                Collections.singletonList(new BasicAuthMiddleware(authManager)));

        try (WsTestClient client = connect()) {
            HttpAnswer answer = client.post(ChannelsCollectionRequestHandler.PATH,
                    new JSONObject().put("id", CREATED).toString(), basicAuth("listener"));
            assertEquals("HTTP/1.1 403 Forbidden", answer.statusLine);
            assertTrue(answer.body.contains("Missing channel right: CREATE"),
                    "the refusal has to name the missing right, not the disabled setting: " + answer.body);
        }
        assertEquals(0, channels.countDynamic(), "a refused create must leave nothing behind");
    }

    @Test
    public void creationIsRefusedWhileDynamicChannelsAreSwitchedOff() throws Exception {
        config.setAuthMode(SHTTPSConfig.AuthMode.NONE);
        //the default, spelled out: this is the setting under test
        config.setAllowDynamicChannelCreation(false);
        startAnonymous(Collections.emptyList());

        try (WsTestClient client = connect()) {
            HttpAnswer answer = client.post(ChannelsCollectionRequestHandler.PATH,
                    new JSONObject().put("id", CREATED).toString(), Collections.emptyMap());
            assertEquals("HTTP/1.1 403 Forbidden", answer.statusLine);
            assertTrue(answer.body.contains("Dynamic channel creation is disabled"),
                    "authorization is off here, so the refusal can only be the setting: " + answer.body);
        }
        assertEquals(0, channels.countDynamic());
    }

    @Test
    public void aCollidingIdIsRefusedWithAConflict() throws Exception {
        startAnonymousWithCreation(Collections.singletonList(new ChannelDefinition(PREDEFINED)));

        try (WsTestClient client = connect()) {
            assertEquals("HTTP/1.1 201 Created", create(client, new JSONObject().put("id", CREATED)).statusLine);

            HttpAnswer again = create(client, new JSONObject().put("id", CREATED));
            assertEquals("HTTP/1.1 409 Conflict", again.statusLine);
            assertTrue(again.body.contains("Channel already exists"),
                    "a taken id and a reached limit share a status code, so the message has to " +
                            "tell them apart: " + again.body);

            HttpAnswer predefined = create(client, new JSONObject().put("id", PREDEFINED));
            assertEquals("HTTP/1.1 409 Conflict", predefined.statusLine,
                    "a predefined channel occupies its id just as much as a dynamic one");
        }
        assertEquals(1, channels.countDynamic());
    }

    @Test
    public void creationStopsAtTheDynamicChannelLimit() throws Exception {
        config.setMaxDynamicChannels(2);
        startAnonymousWithCreation(Collections.singletonList(new ChannelDefinition(PREDEFINED)));

        try (WsTestClient client = connect()) {
            assertEquals("HTTP/1.1 201 Created", create(client, new JSONObject().put("id", "a")).statusLine);
            assertEquals("HTTP/1.1 201 Created", create(client, new JSONObject().put("id", "b")).statusLine,
                    "the predefined channel must not count against the dynamic limit");

            HttpAnswer refused = create(client, new JSONObject().put("id", "c"));
            assertEquals("HTTP/1.1 409 Conflict", refused.statusLine);
            assertTrue(refused.body.contains("Too many dynamic channels (max 2)"),
                    "the limit refusal has to be distinguishable from a taken id: " + refused.body);
        }
        assertEquals(2, channels.countDynamic());
        assertEquals(1, channels.countPredefined());
    }

    @Test
    public void aCreatedChannelIsUsableAndCountedAsDynamic() throws Exception {
        startAnonymousWithCreation(Collections.singletonList(new ChannelDefinition(PREDEFINED)));

        String wsUrl;
        try (WsTestClient client = connect()) {
            HttpAnswer answer = create(client, new JSONObject().put("id", CREATED).put("mode", "echo"));
            assertEquals("HTTP/1.1 201 Created", answer.statusLine);

            JSONObject created = new JSONObject(answer.body);
            assertEquals(CREATED, created.getString("id"));
            assertFalse(created.getBoolean("persistent"), "a channel created over the API is not persistent");
            assertTrue(created.getBoolean("deletable"),
                    "otherwise nothing but the idle sweep could ever remove it");
            wsUrl = created.getString(ChannelsCollectionRequestHandler.FIELD_WS_URL);
            assertEquals(ChannelsPathRequestHandler.PATH_PREFIX + CREATED + "/" +
                    ChannelsPathRequestHandler.ACTION_CONNECT, wsUrl);
        }

        //this is what the "dynamic" count of the channels status scope (§12) reports
        assertEquals(1, channels.countDynamic());
        assertEquals(1, channels.countPredefined());

        try (WsTestClient alice = connect(); WsTestClient bob = connect()) {
            alice.handshake(wsUrl);
            alice.readParticipantsSnapshot();
            bob.handshake(wsUrl);
            bob.readParticipantsSnapshot();
            //a channel created over the API announces presence - there is no field on the creation
            //body to ask for anything else - so alice hears about bob before her own acknowledgement
            assertEquals(ChannelWebSocketHandler.EVENT_TYPE_JOIN, alice.readJson().getString("type"));

            alice.send(1, new JSONObject().put("text", "hi"));
            assertTrue(alice.readJson().getBoolean("ok"));

            JSONObject event = bob.readJson();
            assertEquals("message", event.getString("type"));
            assertEquals("hi", event.getJSONObject("payload").getString("text"));
        }
    }

    @Test
    public void aCreatedChannelKeepsThePasswordAndLimitsItWasAskedFor() throws Exception {
        startAnonymousWithCreation(Collections.emptyList());

        try (WsTestClient client = connect()) {
            HttpAnswer answer = create(client, new JSONObject()
                    .put("id", CREATED)
                    .put("password", "pin")
                    .put("maxParticipants", 3)
                    .put("guestsAllowed", false));
            assertEquals("HTTP/1.1 201 Created", answer.statusLine);

            JSONObject created = new JSONObject(answer.body);
            assertTrue(created.getBoolean("passwordProtected"));
            assertFalse(created.getBoolean("guestsAllowed"));
            assertEquals(3, created.getInt("maxParticipants"),
                    "the requested limit overrides the server-wide default, as it does for a " +
                            "predefined channel");
            assertFalse(answer.body.contains("pin"),
                    "the plain text of the password has no business in the answer: " + answer.body);
        }

        try (WsTestClient client = connect()) {
            assertEquals("HTTP/1.1 403 Forbidden", client.handshakeRaw(wsUrl(CREATED)).get(WsTestClient.STATUS_LINE),
                    "a channel created with a password has to ask for it");
        }
        try (WsTestClient client = connect()) {
            //the same hashing as a user password, so a wrongly wired hash would lock everyone out
            client.handshake(wsUrl(CREATED) + "?password=pin");
        }
    }

    /**
     * The message rate limit can be chosen when a channel is created, and zero is a legal choice
     * meaning "do not throttle this one" - unlike {@code maxParticipants}, where zero is refused
     * because a channel nobody may join is nothing anybody wants.
     */
    @Test
    public void aCreatedChannelCanChooseItsOwnMessageRateLimit() throws Exception {
        startAnonymousWithCreation(Collections.emptyList());
        config.setChannelMessageRateLimitPerSecond(20);

        try (WsTestClient client = connect()) {
            HttpAnswer answer = create(client, new JSONObject().put("id", CREATED)
                    .put(ChannelDefinition.FIELD_MESSAGE_RATE_LIMIT, 3));
            assertEquals("HTTP/1.1 201 Created", answer.statusLine);
            assertEquals(3, new JSONObject(answer.body).getInt(ChannelDefinition.FIELD_MESSAGE_RATE_LIMIT),
                    "the created channel reports the limit it was asked for: " + answer.body);
            assertEquals(3, channels.get(CREATED).messageRateLimit(20));

            HttpAnswer unthrottled = create(client, new JSONObject().put("id", CREATED + "-open")
                    .put(ChannelDefinition.FIELD_MESSAGE_RATE_LIMIT, 0));
            assertEquals("HTTP/1.1 201 Created", unthrottled.statusLine);
            assertEquals(0, channels.get(CREATED + "-open").messageRateLimit(20),
                    "zero is the answer, not a missing one: this channel asked not to be throttled");

            //a channel that says nothing is still the server's business
            HttpAnswer silent = create(client, new JSONObject().put("id", CREATED + "-default"));
            assertEquals("HTTP/1.1 201 Created", silent.statusLine);
            assertFalse(silent.body.contains(ChannelDefinition.FIELD_MESSAGE_RATE_LIMIT),
                    "a channel that does not override the limit does not report one: " + silent.body);
            assertEquals(20, channels.get(CREATED + "-default").messageRateLimit(20));

            HttpAnswer negative = create(client, new JSONObject().put("id", CREATED + "-bad")
                    .put(ChannelDefinition.FIELD_MESSAGE_RATE_LIMIT, -1));
            assertEquals("HTTP/1.1 400 Bad Request", negative.statusLine);
            assertTrue(negative.body.contains("zero (no limit) or a positive number"),
                    "the refusal has to say that zero is allowed here: " + negative.body);
        }
    }

    @Test
    public void aCreatedStateChannelStartsFromTheGivenDocument() throws Exception {
        startAnonymousWithCreation(Collections.emptyList());

        try (WsTestClient client = connect()) {
            JSONObject initialState = new JSONObject().put("players", new JSONObject().put("alice", 3));
            HttpAnswer answer = create(client, new JSONObject()
                    .put("id", CREATED).put("mode", "state").put("initialState", initialState));
            assertEquals("HTTP/1.1 201 Created", answer.statusLine);
            assertEquals("state", new JSONObject(answer.body).getString("mode"));
        }

        try (WsTestClient client = connect()) {
            client.handshake(wsUrl(CREATED));
            JSONObject snapshot = client.readJson();
            assertEquals("state", snapshot.getString("type"));
            assertEquals(3, snapshot.getJSONObject("state").getJSONObject("players").getInt("alice"),
                    "a STATE channel starts from the document its creator gave it");
        }
    }

    @Test
    public void anIdIsGeneratedWhenTheBodyOmitsIt() throws Exception {
        startAnonymousWithCreation(Collections.emptyList());

        try (WsTestClient client = connect()) {
            //an empty object and no body at all both mean "everything at its default"
            String first = new JSONObject(create(client, new JSONObject()).body).getString("id");
            HttpAnswer bodyless = client.post(ChannelsCollectionRequestHandler.PATH, null,
                    Collections.emptyMap());
            assertEquals("HTTP/1.1 201 Created", bodyless.statusLine,
                    "a create with no body at all asks for a channel with every default");
            String second = new JSONObject(bodyless.body).getString("id");

            assertTrue(ChannelDefinition.isValidId(first), "a generated id has to be url-safe: " + first);
            assertTrue(ChannelDefinition.isValidId(second));
            assertNotEquals(first, second, "each create gets an id of its own");

            assertEquals("HTTP/1.1 200 Ok",
                    client.get(ChannelsPathRequestHandler.PATH_PREFIX + first, Collections.emptyMap()).statusLine,
                    "the generated id has to be the one the channel is reachable under");
        }
        assertEquals(2, channels.countDynamic());
    }

    @Test
    public void aChannelWithAConnectedParticipantIsNotCollected() throws Exception {
        config.setChannelIdleTimeoutMillis(IDLE_TIMEOUT_MS);
        startAnonymousWithCreation(Collections.emptyList());

        try (WsTestClient rest = connect(); WsTestClient participant = connect()) {
            String wsUrl = new JSONObject(create(rest, new JSONObject().put("id", CREATED)).body)
                    .getString(ChannelsCollectionRequestHandler.FIELD_WS_URL);
            participant.handshake(wsUrl);
            awaitParticipants(CREATED, 1);

            Thread.sleep(IDLE_TIMEOUT_MS * 3L);

            assertEquals(0, channels.collectIdleChannels(config),
                    "a channel somebody is connected to is in use, however long ago the last message was");
            assertEquals("HTTP/1.1 200 Ok",
                    rest.get(ChannelsPathRequestHandler.PATH_PREFIX + CREATED, Collections.emptyMap()).statusLine);
        }
    }

    @Test
    public void anAbandonedChannelIsCollectedAfterTheIdleTimeout() throws Exception {
        config.setChannelIdleTimeoutMillis(IDLE_TIMEOUT_MS);
        startAnonymousWithCreation(Collections.singletonList(new ChannelDefinition(PREDEFINED)));

        try (WsTestClient rest = connect()) {
            String wsUrl = new JSONObject(create(rest, new JSONObject().put("id", CREATED)).body)
                    .getString(ChannelsCollectionRequestHandler.FIELD_WS_URL);
            try (WsTestClient participant = connect()) {
                participant.handshake(wsUrl);
                awaitParticipants(CREATED, 1);
            }
            //the server notices the dropped socket on its own thread, and only then does the idle
            //clock start - collecting before that would pass for the wrong reason
            awaitParticipants(CREATED, 0);
            Thread.sleep(IDLE_TIMEOUT_MS * 3L);

            assertEquals(1, channels.collectIdleChannels(config));
            assertEquals("HTTP/1.1 404 Not found",
                    rest.get(ChannelsPathRequestHandler.PATH_PREFIX + CREATED, Collections.emptyMap()).statusLine);
            assertEquals(0, channels.countDynamic());

            assertEquals(1, channels.countPredefined(), "a predefined channel is never collected");
            assertEquals("HTTP/1.1 200 Ok",
                    rest.get(ChannelsPathRequestHandler.PATH_PREFIX + PREDEFINED, Collections.emptyMap()).statusLine,
                    "the predefined channel was empty the whole time and still has to be there");
        }
    }

    @Test
    public void deletingANonDeletableChannelIsRefused() throws Exception {
        startAnonymousWithCreation(Collections.singletonList(new ChannelDefinition(PREDEFINED)));

        try (WsTestClient client = connect()) {
            HttpAnswer answer = client.delete(ChannelsPathRequestHandler.PATH_PREFIX + PREDEFINED,
                    Collections.emptyMap());
            assertEquals("HTTP/1.1 403 Forbidden", answer.statusLine);
            assertTrue(answer.body.contains("not deletable"), answer.body);

            assertEquals("HTTP/1.1 200 Ok",
                    client.get(ChannelsPathRequestHandler.PATH_PREFIX + PREDEFINED, Collections.emptyMap()).statusLine,
                    "the refused delete must have left the channel alone");
        }
        assertEquals(1, channels.countPredefined());
    }

    @Test
    public void deletingAChannelWithoutTheDeleteRightIsRefused() throws Exception {
        config.setAuthMode(SHTTPSConfig.AuthMode.BASIC_AUTH);
        config.setAllowDynamicChannelCreation(true);
        AuthManager authManager = new BasicAuthManager(userStoreWith(
                userWith("listener", User.ChannelRights.CONNECT),
                userWith("owner", User.ChannelRights.CONNECT, User.ChannelRights.CREATE,
                        User.ChannelRights.DELETE)));
        start(Collections.emptyList(), authManager,
                Collections.singletonList(new BasicAuthMiddleware(authManager)));

        try (WsTestClient client = connect()) {
            assertEquals("HTTP/1.1 201 Created", client.post(ChannelsCollectionRequestHandler.PATH,
                    new JSONObject().put("id", CREATED).toString(), basicAuth("owner")).statusLine);

            String path = ChannelsPathRequestHandler.PATH_PREFIX + CREATED;
            HttpAnswer refused = client.delete(path, basicAuth("listener"));
            assertEquals("HTTP/1.1 403 Forbidden", refused.statusLine);
            assertTrue(refused.body.contains("Missing channel right: DELETE"),
                    "the channel is perfectly deletable - what is missing is the right: " + refused.body);
            assertEquals(1, channels.countDynamic(), "the refused delete must have changed nothing");

            assertEquals("HTTP/1.1 200 Ok", client.delete(path, basicAuth("owner")).statusLine,
                    "the same delete from a user that holds the right goes through");
        }
        assertEquals(0, channels.countDynamic());
    }

    @Test
    public void deletingAChannelDisconnectsItsParticipantsWith4410() throws Exception {
        startAnonymousWithCreation(Collections.emptyList());

        try (WsTestClient rest = connect(); WsTestClient participant = connect()) {
            String wsUrl = new JSONObject(create(rest, new JSONObject().put("id", CREATED)).body)
                    .getString(ChannelsCollectionRequestHandler.FIELD_WS_URL);
            participant.handshake(wsUrl);
            awaitParticipants(CREATED, 1);

            HttpAnswer deleted = rest.delete(ChannelsPathRequestHandler.PATH_PREFIX + CREATED,
                    Collections.emptyMap());
            assertEquals("HTTP/1.1 200 Ok", deleted.statusLine);
            assertTrue(new JSONObject(deleted.body).getBoolean("deleted"));

            assertEquals(ChannelWebSocketHandler.CLOSE_CODE_CHANNEL_CLOSED, participant.awaitCloseCode(),
                    "a participant of a deleted channel has to be told why it is going away");

            assertEquals("HTTP/1.1 404 Not found",
                    rest.get(ChannelsPathRequestHandler.PATH_PREFIX + CREATED, Collections.emptyMap()).statusLine);
            assertEquals(0, channels.countDynamic());
        }
    }

    private static String wsUrl(String channelId) {
        return ChannelsPathRequestHandler.PATH_PREFIX + channelId + "/" +
                ChannelsPathRequestHandler.ACTION_CONNECT;
    }

    private HttpAnswer create(WsTestClient client, JSONObject body) throws IOException {
        return client.post(ChannelsCollectionRequestHandler.PATH, body.toString(), Collections.emptyMap());
    }

    /**
     * The four numbers {@code GET /api/system/status} reports under its {@code channels} scope
     * (§12). {@code StatusRequestHandler} reads them straight off the {@link ChannelManager} and the
     * config, so checking them here checks what that scope answers with, without standing up a
     * whole {@code SHTTPSApp} for four fields.
     */
    @Test
    public void theStatusScopeCountsPredefinedDynamicAndParticipants() throws Exception {
        startAnonymousWithCreation(Collections.singletonList(new ChannelDefinition(PREDEFINED)));

        assertTrue(config.isChannelsEnabled());
        assertEquals(1, channels.countPredefined());
        assertEquals(0, channels.countDynamic());
        assertEquals(0, channels.totalParticipants());

        try (WsTestClient rest = connect()) {
            assertEquals("HTTP/1.1 201 Created", create(rest, new JSONObject().put("id", CREATED)).statusLine);
            assertEquals(1, channels.countPredefined(), "creating one must not have moved the other");
            assertEquals(1, channels.countDynamic());

            try (WsTestClient here = connect(); WsTestClient there = connect()) {
                here.handshake(connectPath(PREDEFINED));
                there.handshake(wsUrl(CREATED));
                awaitParticipants(PREDEFINED, 1);
                awaitParticipants(CREATED, 1);

                assertEquals(2, channels.totalParticipants(),
                        "the total is across every channel, not the biggest one");
            }

            awaitParticipants(PREDEFINED, 0);
            awaitParticipants(CREATED, 0);
            assertEquals(0, channels.totalParticipants(), "participants that left are not counted");
        }

        assertEquals(1, channels.remove(CREATED) == null ? 0 : 1);
        assertEquals(0, channels.countDynamic(), "a deleted channel is no longer counted");
        assertEquals(1, channels.countPredefined());
    }

    private String connectPath(String channelId) {
        return ChannelsPathRequestHandler.PATH_PREFIX + channelId + "/" +
                ChannelsPathRequestHandler.ACTION_CONNECT;
    }

    /**
     * Waits for the channel to hold that many participants. Both joining and leaving happen on the
     * server's own connection thread, after the handshake the test saw and after the socket the
     * test closed, so anything counting participants has to wait for them rather than assume.
     */
    private void awaitParticipants(String channelId, int expected) throws InterruptedException {
        Channel channel = channels.get(channelId);
        assertNotNull(channel, "no such channel: " + channelId);
        for (int i = 0; i < 300; i++) {
            if (channel.participantsCount() == expected) return;
            Thread.sleep(10);
        }
        assertEquals(expected, channel.participantsCount(), "the participant count never settled");
    }

    private void startAnonymousWithCreation(List<ChannelDefinition> definitions) throws IOException {
        config.setAuthMode(SHTTPSConfig.AuthMode.NONE);
        config.setAllowDynamicChannelCreation(true);
        startAnonymous(definitions);
    }

    private void startAnonymous(List<ChannelDefinition> definitions) throws IOException {
        config.setAuthMode(SHTTPSConfig.AuthMode.NONE);
        start(definitions, new DummyAuthManager(), Collections.emptyList());
    }

    private void start(List<ChannelDefinition> definitions, AuthManager authManager,
                       List<Middleware> authMiddlewares) throws IOException {
        config.setChannelsEnabled(true);
        config.setPredefinedChannels(definitions);

        channels = new ChannelManager();
        channels.applyPredefinedChannels(config);

        ChannelWebSocketHandler webSocketHandler =
                new ChannelWebSocketHandler(channels, config, authManager);
        //keep the tests deterministic: no pings appearing in the middle of the expected frames
        webSocketHandler.options.idlePingIntervalMillis = 0;

        Router router = new Router(null, Collections.emptyList());
        router.addRoute(ChannelsCollectionRequestHandler.PATH,
                Set.of(Request.METHOD_GET, Request.METHOD_POST),
                new ChannelsCollectionRequestHandler(channels, config, authManager), authMiddlewares);
        router.addRouteByPathPrefix(ChannelsPathRequestHandler.PATH_PREFIX,
                new ChannelsPathRequestHandler(channels, config, authManager, webSocketHandler),
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

    private static Map<String, String> basicAuth(String identity) {
        String credentials = identity + ":" + USER_PASSWORD;
        return Collections.singletonMap("Authorization", "Basic " +
                Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
    }
}
