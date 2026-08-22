package com.phlox.simpleserver.channels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.phlox.simpleserver.handlers.channels.ChannelAccess;
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
 * One-shot publishing over HTTP (§10): {@code POST /api/channels/{id}/message}, for a cron job or a
 * sensor that has one thing to say and no reason to hold a socket open to say it.
 * <p>
 * Built like {@link ChannelsIntegrationTest} and {@link ChannelsManagementTest} - a real server on a
 * loopback port and the same hand-rolled client - because the point of every test here is what a
 * plain HTTP request causes a connected WebSocket to see.
 */
@Timeout(30)
public class ChannelsPublishTest {
    private static final String CHANNEL = "lobby";
    private static final String USER_PASSWORD = "s3cret";
    private static final String CHANNEL_PASSWORD = "pin-1234";

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
    public void publishedMessageReachesTheConnectedParticipantsTaggedAsHttp() throws Exception {
        startAnonymous(Collections.singletonList(new ChannelDefinition(CHANNEL)));

        try (WsTestClient listener = connect(); WsTestClient publisher = connect()) {
            listener.handshake(connectPath(CHANNEL));
            //authorization is off, so the listener may list participants and is greeted with the
            //list; the join itself lands on the server's own thread, after the handshake this test saw
            listener.readParticipantsSnapshot();
            awaitParticipants(CHANNEL, 1);

            HttpAnswer answer = publish(publisher, new JSONObject()
                    .put("payload", new JSONObject().put("text", "hi"))
                    .put("senderLabel", "cron"), Collections.emptyMap());

            assertEquals("HTTP/1.1 200 Ok", answer.statusLine);
            JSONObject published = new JSONObject(answer.body);
            assertTrue(published.getBoolean("delivered"));
            assertEquals(1, published.getInt("recipientCount"));
            assertEquals(1, published.getLong("seq"), "the first event of a fresh channel is seq 1");

            JSONObject event = listener.readJson();
            assertEquals("message", event.getString("type"));
            assertEquals("http", event.getString("source"),
                    "a recipient has to be able to tell a pushed message from a live one");
            assertEquals("hi", event.getJSONObject("payload").getString("text"));
            assertEquals(1, event.getLong("seq"), "published messages share the channel's counter");

            JSONObject from = event.getJSONObject("from");
            assertTrue(from.isNull("participantId"), "the publisher joined nothing: " + from);
            assertTrue(from.isNull("identity"), "authorization is off, so there is no identity");
            assertEquals("cron", from.getString("label"));

            //the publisher must be invisible: not a participant, not counted, not listed
            assertEquals(1, channels.get(CHANNEL).participantsCount(),
                    "publishing must not have registered anybody");
            HttpAnswer participants = publisher.get(participantsPath(CHANNEL), Collections.emptyMap());
            assertEquals("HTTP/1.1 200 Ok", participants.statusLine);
            assertEquals(1, new JSONObject(participants.body).getJSONArray("participants").length(),
                    "the publisher must not appear in the participant list: " + participants.body);
        }
    }

    @Test
    public void publishingWithoutASenderLabelLeavesTheFieldOut() throws Exception {
        startAnonymous(Collections.singletonList(new ChannelDefinition(CHANNEL)));

        try (WsTestClient listener = connect(); WsTestClient publisher = connect()) {
            listener.handshake(connectPath(CHANNEL));
            listener.readParticipantsSnapshot();
            awaitParticipants(CHANNEL, 1);

            assertEquals("HTTP/1.1 200 Ok", publish(publisher,
                    new JSONObject().put("payload", "plain string payload"),
                    Collections.emptyMap()).statusLine);

            JSONObject event = listener.readJson();
            assertEquals("plain string payload", event.getString("payload"),
                    "the payload is relayed unchanged, whatever JSON it is");
            assertFalse(event.getJSONObject("from").has("label"),
                    "an absent senderLabel is absent, not an empty string");
        }
    }

    @Test
    public void publishingNothingIsABadRequestRatherThanAnError() throws Exception {
        startAnonymous(Collections.singletonList(new ChannelDefinition(CHANNEL)));

        //no body at all: a client that forgot the payload, and the commonest way to send nothing
        try (WsTestClient publisher = connect()) {
            HttpAnswer answer = publisher.post(messagePath(CHANNEL), null, Collections.emptyMap());
            assertEquals("HTTP/1.1 400 Bad Request", answer.statusLine);
            assertTrue(answer.body.contains("Missing payload"), answer.body);
        }
        try (WsTestClient publisher = connect()) {
            assertEquals("HTTP/1.1 400 Bad Request", publisher.post(messagePath(CHANNEL),
                    "{not json", Collections.emptyMap()).statusLine);
        }
        try (WsTestClient publisher = connect()) {
            assertEquals("HTTP/1.1 400 Bad Request", publish(publisher,
                    new JSONObject().put("senderLabel", "cron"), Collections.emptyMap()).statusLine);
        }
        assertEquals(0, channels.get(CHANNEL).currentSeq(),
                "a refused publish must not have taken an event number");
    }

    @Test
    public void publishingIntoAStateChannelIsRefused() throws Exception {
        startAnonymous(Collections.singletonList(new ChannelDefinition(CHANNEL, Channel.Mode.STATE,
                new JSONObject().put("round", 1), null, false, true, true, null)));

        try (WsTestClient publisher = connect()) {
            HttpAnswer answer = publish(publisher,
                    new JSONObject().put("payload", new JSONObject().put("text", "hi")),
                    Collections.emptyMap());

            assertEquals("HTTP/1.1 409 Conflict", answer.statusLine,
                    "a state channel is changed with commands, so this is a mistake worth naming");
            assertTrue(answer.body.contains(CHANNEL), "the refusal has to name the channel: " + answer.body);
        }
        assertEquals(0, channels.get(CHANNEL).currentSeq(),
                "a refused publish must not have taken an event number");
    }

    @Test
    public void publishingWithoutTheChannelPasswordIsRefused() throws Exception {
        startAnonymous(Collections.singletonList(new ChannelDefinition(CHANNEL, Channel.Mode.ECHO,
                null, null, false, true, true,
                ChannelDefinition.hashChannelPassword(CHANNEL_PASSWORD))));

        JSONObject body = new JSONObject().put("payload", new JSONObject().put("text", "hi"));

        try (WsTestClient publisher = connect()) {
            HttpAnswer answer = publish(publisher, body, Collections.emptyMap());
            assertEquals("HTTP/1.1 403 Forbidden", answer.statusLine);
            assertTrue(answer.body.contains("password"),
                    "the refusal has to be about the password: " + answer.body);
        }
        //a wrong one is refused the same way as none at all
        try (WsTestClient publisher = connect()) {
            assertEquals("HTTP/1.1 403 Forbidden",
                    publish(publisher, body, Collections.emptyMap(), "?password=wrong").statusLine);
        }
        assertEquals(0, channels.get(CHANNEL).currentSeq(),
                "a refused publish must not have taken an event number");

        //the right one gets through, which is what makes the refusals above about the password
        try (WsTestClient publisher = connect()) {
            assertEquals("HTTP/1.1 200 Ok",
                    publish(publisher, body, Collections.emptyMap(),
                            "?password=" + CHANNEL_PASSWORD).statusLine);
        }
        //and so does the header form, which wins over the query parameter when both are given
        try (WsTestClient publisher = connect()) {
            assertEquals("HTTP/1.1 200 Ok", publish(publisher, body,
                    Collections.singletonMap(ChannelAccess.HEADER_CHANNEL_PASSWORD, CHANNEL_PASSWORD),
                    "?password=wrong").statusLine);
        }
        //the same pairing the other way round has to be refused, or "the header wins" would just be
        //"either one will do"
        try (WsTestClient publisher = connect()) {
            assertEquals("HTTP/1.1 403 Forbidden", publish(publisher, body,
                    Collections.singletonMap(ChannelAccess.HEADER_CHANNEL_PASSWORD, "wrong"),
                    "?password=" + CHANNEL_PASSWORD).statusLine);
        }
        assertEquals(2, channels.get(CHANNEL).currentSeq());
    }

    @Test
    public void publishingWithoutThePostRightIsRefused() throws Exception {
        config.setAuthMode(SHTTPSConfig.AuthMode.BASIC_AUTH);
        AuthManager authManager = new BasicAuthManager(userStoreWith(
                userWith("listener", User.ChannelRights.CONNECT),
                userWith("speaker", User.ChannelRights.CONNECT, User.ChannelRights.POST)));
        start(Collections.singletonList(new ChannelDefinition(CHANNEL)), authManager,
                Collections.singletonList(new BasicAuthMiddleware(authManager)));

        JSONObject body = new JSONObject().put("payload", new JSONObject().put("text", "hi"));

        try (WsTestClient publisher = connect()) {
            HttpAnswer answer = publish(publisher, body, basicAuth("listener"));
            assertEquals("HTTP/1.1 403 Forbidden", answer.statusLine);
            assertTrue(answer.body.contains("Missing channel right: POST"),
                    "the refusal has to name the missing right: " + answer.body);
        }
        assertEquals(0, channels.get(CHANNEL).currentSeq(),
                "a refused publish must not have taken an event number");

        //the same request from a user that holds POST goes through, and carries its identity
        try (WsTestClient listener = connect(); WsTestClient publisher = connect()) {
            listener.handshake(connectPath(CHANNEL), basicAuth("listener"));
            awaitParticipants(CHANNEL, 1);

            assertEquals("HTTP/1.1 200 Ok", publish(publisher, body, basicAuth("speaker")).statusLine);

            JSONObject from = listener.readJson().getJSONObject("from");
            assertEquals("speaker", from.getString("identity"),
                    "an authenticated publisher is named, even though it is not a participant");
            assertTrue(from.isNull("participantId"));
        }
    }

    private HttpAnswer publish(WsTestClient client, JSONObject body, Map<String, String> headers)
            throws IOException {
        return publish(client, body, headers, "");
    }

    private HttpAnswer publish(WsTestClient client, JSONObject body, Map<String, String> headers,
                               String query) throws IOException {
        return client.post(messagePath(CHANNEL) + query, body.toString(), headers);
    }

    private static String messagePath(String channelId) {
        return ChannelsPathRequestHandler.PATH_PREFIX + channelId + "/" +
                ChannelsPathRequestHandler.ACTION_MESSAGE;
    }

    private static String connectPath(String channelId) {
        return ChannelsPathRequestHandler.PATH_PREFIX + channelId + "/" +
                ChannelsPathRequestHandler.ACTION_CONNECT;
    }

    private static String participantsPath(String channelId) {
        return ChannelsPathRequestHandler.PATH_PREFIX + channelId + "/" +
                ChannelsPathRequestHandler.ACTION_PARTICIPANTS;
    }

    /**
     * Waits for the channel to hold that many participants. Joining happens on the server's own
     * connection thread, after the handshake the test saw, so anything counting participants - and
     * {@code recipientCount} is exactly that - has to wait for them rather than assume.
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
