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
 * Presence, §9: the participant list a connection is greeted with, and the {@code join}/{@code
 * leave} events that keep it true afterwards.
 * <p>
 * Both are behind {@code LIST_PARTICIPANTS}, so these tests run with authorization on - with it off
 * every connection holds every right and there would be nobody to check the negative half against.
 * The negative half is the point: a participant without the right must not learn that somebody came
 * or went, and "no frame arrived" is asserted by making the <i>next</i> frame the one the test
 * expects, so an event that should not have been sent shows up as a wrong frame rather than as a
 * hang.
 */
@Timeout(30)
public class ChannelsPresenceTest {
    private static final String CHANNEL = "lobby";
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
    public void aJoinerHoldingTheRightIsGreetedWithEverybodyWhoIsAlreadyThere() throws Exception {
        startWithUsers(echoChannel(true),
                watcher("first"), watcher("second"));

        try (WsTestClient first = connect(); WsTestClient second = connect()) {
            first.handshake(connectPath(), basicAuth("first"));
            JSONObject alone = first.readParticipantsSnapshot();
            assertEquals(0, alone.getLong("seq"), "nothing has happened in this channel yet");
            assertEquals(1, alone.getJSONArray("participants").length(),
                    "the list a connection is greeted with contains itself: " + alone);

            second.handshake(connectPath(), basicAuth("second"));
            JSONObject snapshot = second.readParticipantsSnapshot();

            JSONArray participants = snapshot.getJSONArray("participants");
            assertEquals(2, participants.length(), "both connections are in the channel: " + snapshot);
            List<String> identities = new ArrayList<>();
            for (int i = 0; i < participants.length(); i++) {
                JSONObject participant = participants.getJSONObject(i);
                assertNotNull(participant.getString("participantId"));
                assertTrue(participant.getLong("joinedAt") > 0);
                identities.add(participant.getString("identity"));
            }
            assertTrue(identities.contains("first") && identities.contains("second"),
                    "an authenticated participant is listed by name: " + participants);
        }
    }

    @Test
    public void aJoinerWithoutTheRightIsToldAboutNobodyAtAll() throws Exception {
        startWithUsers(echoChannel(true),
                watcher("watcher"),
                userWith("plain", User.ChannelRights.CONNECT, User.ChannelRights.POST));

        try (WsTestClient watcher = connect(); WsTestClient plain = connect()) {
            watcher.handshake(connectPath(), basicAuth("watcher"));
            watcher.readParticipantsSnapshot();

            plain.handshake(connectPath(), basicAuth("plain"));
            //no greeting for a connection that may not see the list: its first frame is the answer
            //to the first thing it sends
            plain.send(1, new JSONObject().put("text", "hi"));
            JSONObject first = plain.readJson();
            assertTrue(first.optBoolean("ok"),
                    "a connection without LIST_PARTICIPANTS was told about the others: " + first);
            assertEquals(1, first.getInt("id"));
        }
    }

    @Test
    public void joinAndLeaveReachAWatcherAndNobodyElse() throws Exception {
        startWithUsers(echoChannel(true),
                watcher("watcher"),
                userWith("plain", User.ChannelRights.CONNECT, User.ChannelRights.POST),
                userWith("visitor", User.ChannelRights.CONNECT, User.ChannelRights.POST));

        try (WsTestClient watcher = connect(); WsTestClient plain = connect()) {
            watcher.handshake(connectPath(), basicAuth("watcher"));
            watcher.readParticipantsSnapshot();
            plain.handshake(connectPath(), basicAuth("plain"));
            //the watcher hears about the participant that has no right to hear about anybody
            assertEquals("plain", presence(watcher, ChannelWebSocketHandler.EVENT_TYPE_JOIN)
                    .getString("identity"));

            String visitorId;
            try (WsTestClient visitor = connect()) {
                visitor.handshake(connectPath(), basicAuth("visitor"));

                JSONObject joined = presence(watcher, ChannelWebSocketHandler.EVENT_TYPE_JOIN);
                assertEquals("visitor", joined.getString("identity"));
                visitorId = joined.getString("participantId");
                assertTrue(joined.getLong("joinedAt") > 0);
            }

            JSONObject left = presence(watcher, ChannelWebSocketHandler.EVENT_TYPE_LEAVE);
            assertEquals("visitor", left.getString("identity"));
            assertEquals(visitorId, left.getString("participantId"),
                    "a leave names the same participant the join did, so a client can drop that entry");

            //and none of the three events reached the participant without the right: the next thing
            //in its stream is an ordinary message, sent after all of them
            watcher.send(1, new JSONObject().put("text", "still here?"));
            JSONObject seenByPlain = plain.readJson();
            assertEquals(ChannelWebSocketHandler.EVENT_TYPE_MESSAGE, seenByPlain.getString("type"),
                    "a presence event reached a participant without LIST_PARTICIPANTS: " + seenByPlain);
            assertEquals("still here?", seenByPlain.getJSONObject("payload").getString("text"));
        }
    }

    @Test
    public void aChannelThatDoesNotNotifyPresenceStillGreetsButNeverAnnounces() throws Exception {
        startWithUsers(echoChannel(false),
                watcher("watcher"),
                userWith("visitor", User.ChannelRights.CONNECT, User.ChannelRights.POST));

        try (WsTestClient watcher = connect(); WsTestClient visitor = connect()) {
            watcher.handshake(connectPath(), basicAuth("watcher"));
            //the greeting is not a per-channel setting: the server has the list in its hand anyway
            watcher.readParticipantsSnapshot();

            visitor.handshake(connectPath(), basicAuth("visitor"));
            visitor.send(1, new JSONObject().put("text", "quietly"));

            JSONObject seen = watcher.readJson();
            assertEquals(ChannelWebSocketHandler.EVENT_TYPE_MESSAGE, seen.getString("type"),
                    "a channel with notifyPresence=false announced a join: " + seen);
            assertEquals("quietly", seen.getJSONObject("payload").getString("text"));
        }
    }

    @Test
    public void aStateChannelSendsTheDocumentFirstAndThenTheParticipants() throws Exception {
        ChannelDefinition definition = new ChannelDefinition(CHANNEL, Channel.Mode.STATE,
                new JSONObject().put("round", 1), null, false, true, true, null);
        startWithUsers(definition, watcher("watcher"));

        try (WsTestClient watcher = connect()) {
            watcher.handshake(connectPath(), basicAuth("watcher"));

            //the document first - it is what the connection works from - and the list behind it,
            //both numbered with the same seq
            JSONObject state = watcher.readJson();
            assertEquals(ChannelWebSocketHandler.EVENT_TYPE_STATE, state.getString("type"));
            assertEquals(1, state.getJSONObject("state").getInt("round"));

            JSONObject participants = watcher.readParticipantsSnapshot();
            assertEquals(state.getLong("seq"), participants.getLong("seq"),
                    "both snapshots describe the channel as of the same event");
            assertEquals(1, participants.getJSONArray("participants").length());
        }
    }

    /** The participant of the next presence event of the expected kind. */
    private static JSONObject presence(WsTestClient client, String type) throws IOException {
        JSONObject event = client.readJson();
        assertEquals(type, event.getString("type"), "expected a " + type + " event, got: " + event);
        assertFalse(event.isNull("seq"), "every event carries the channel's seq: " + event);
        return event.getJSONObject("participant");
    }

    private static ChannelDefinition echoChannel(boolean notifyPresence) {
        return new ChannelDefinition(CHANNEL, Channel.Mode.ECHO, null, null,
                false, notifyPresence, true, null);
    }

    private String connectPath() {
        return ChannelsPathRequestHandler.PATH_PREFIX + CHANNEL + "/" +
                ChannelsPathRequestHandler.ACTION_CONNECT;
    }

    private void startWithUsers(ChannelDefinition definition, User... users) throws IOException {
        config.setAuthMode(SHTTPSConfig.AuthMode.BASIC_AUTH);
        config.setUsers(new ArrayList<>(List.of(users)));
        AuthManager authManager = new BasicAuthManager(new ConfigBasedUserStore(config));
        start(Collections.singletonList(definition), authManager,
                Collections.singletonList(new BasicAuthMiddleware(authManager)));
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

    private static User watcher(String identity) {
        return userWith(identity, User.ChannelRights.CONNECT, User.ChannelRights.POST,
                User.ChannelRights.LIST_PARTICIPANTS);
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
