package com.phlox.simpleserver.channels;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.phlox.server.SimpleHttpServer;
import com.phlox.server.handlers.router.Router;
import com.phlox.server.handlers.router.middleware.Middleware;
import com.phlox.server.request.Request;
import com.phlox.server.websocket.WebSocketCloseCodes;
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
 * Raw binary relay (§7.1): a channel carrying {@code binaryAllowed} passes binary frames between its
 * participants byte for byte, with no envelope and no acknowledgement.
 * <p>
 * Built like {@link ChannelsIntegrationTest} - a real server on a loopback port and the same
 * hand-rolled client - because what is being tested is precisely what comes back out of a socket.
 * <p>
 * The two properties worth guarding here, beyond "the bytes arrive", are that a blob takes no
 * {@code seq} and that a channel without the flag refuses one <i>without</i> dropping the
 * connection: both are decisions that would be easy to undo by accident later.
 */
@Timeout(30)
public class ChannelsBinaryTest {
    private static final String CHANNEL = "blobs";
    private static final String USER_PASSWORD = "s3cret";

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
    public void aBlobReachesTheOtherParticipantsByteForByte() throws Exception {
        startAnonymous(Collections.singletonList(binaryChannel(CHANNEL, Channel.Mode.ECHO)));

        try (WsTestClient alice = connect(); WsTestClient bob = connect()) {
            alice.handshake(connectPath(CHANNEL));
            alice.readParticipantsSnapshot();
            bob.handshake(connectPath(CHANNEL));
            bob.readParticipantsSnapshot();
            alice.readJson();//bob's join
            awaitParticipants(CHANNEL, 2);

            //deliberately not valid UTF-8 and full of zero bytes: the point of a binary channel is
            //carrying what a text one cannot
            byte[] blob = new byte[]{0x00, (byte) 0xFF, (byte) 0xC3, 0x28, 0x00, 0x7F, (byte) 0x80};
            alice.sendBinary(blob);

            assertArrayEquals(blob, bob.readBinary(),
                    "a relayed blob has to arrive exactly as it was sent, with nothing wrapped around it");
        }
    }

    @Test
    public void aBlobBiggerThanOneFrameSurvivesFragmentation() throws Exception {
        startAnonymous(Collections.singletonList(binaryChannel(CHANNEL, Channel.Mode.ECHO)));

        try (WsTestClient alice = connect(); WsTestClient bob = connect()) {
            alice.handshake(connectPath(CHANNEL));
            alice.readParticipantsSnapshot();
            bob.handshake(connectPath(CHANNEL));
            bob.readParticipantsSnapshot();
            alice.readJson();//bob's join
            awaitParticipants(CHANNEL, 2);

            //past outgoingFrameLength (64 KiB), so the relay leaves the server as a BINARY frame
            //followed by continuations - the shape anything worth sending as binary actually has
            byte[] blob = new byte[150_000];
            for (int i = 0; i < blob.length; i++) {
                blob[i] = (byte) (i * 31);
            }
            alice.sendBinary(blob);

            assertArrayEquals(blob, bob.readBinary(),
                    "a fragmented blob has to arrive whole and in order");
        }
    }

    @Test
    public void theSenderGetsItsOwnBlobBackOnlyWithEchoToSelf() throws Exception {
        startAnonymous(Collections.singletonList(binaryChannel(CHANNEL, Channel.Mode.ECHO)));

        byte[] blob = "not-json".getBytes(StandardCharsets.UTF_8);

        try (WsTestClient alice = connect(); WsTestClient bob = connect()) {
            alice.handshake(connectPath(CHANNEL) + "?echoToSelf=true");
            alice.readParticipantsSnapshot();
            bob.handshake(connectPath(CHANNEL));
            bob.readParticipantsSnapshot();
            alice.readJson();//bob's join
            awaitParticipants(CHANNEL, 2);

            alice.sendBinary(blob);
            assertArrayEquals(blob, alice.readBinary(),
                    "echoToSelf has to cover blobs as well as messages");
            assertArrayEquals(blob, bob.readBinary());
        }

        //and the other way round: without the parameter the sender hears nothing of its own blob,
        //which is checked by making it read the next thing that does arrive
        startAnonymous(Collections.singletonList(binaryChannel(CHANNEL, Channel.Mode.ECHO)));
        try (WsTestClient alice = connect(); WsTestClient bob = connect()) {
            alice.handshake(connectPath(CHANNEL));
            alice.readParticipantsSnapshot();
            bob.handshake(connectPath(CHANNEL));
            bob.readParticipantsSnapshot();
            alice.readJson();//bob's join
            awaitParticipants(CHANNEL, 2);

            alice.sendBinary(blob);
            bob.readBinary();
            //bob says something back; if alice had been echoed her own blob, this read would return
            //it instead and fail on the frame type
            bob.send(1, new JSONObject().put("text", "got it"));
            assertEquals("got it", alice.readJson().getJSONObject("payload").getString("text"),
                    "alice received her own blob back although echoToSelf was not requested");
        }
    }

    @Test
    public void aBlobTakesNoSeqAndLeavesTheTextSequenceDense() throws Exception {
        startAnonymous(Collections.singletonList(binaryChannel(CHANNEL, Channel.Mode.ECHO)));

        try (WsTestClient alice = connect(); WsTestClient bob = connect()) {
            alice.handshake(connectPath(CHANNEL));
            alice.readParticipantsSnapshot();
            bob.handshake(connectPath(CHANNEL));
            bob.readParticipantsSnapshot();
            alice.readJson();//bob's join
            awaitParticipants(CHANNEL, 2);

            alice.send(1, new JSONObject().put("text", "before"));
            assertEquals(1, alice.readJson().getLong("seq"), "the first event of a fresh channel");
            assertEquals(1, bob.readJson().getLong("seq"));

            alice.sendBinary(new byte[]{1, 2, 3});
            assertArrayEquals(new byte[]{1, 2, 3}, bob.readBinary());

            alice.send(2, new JSONObject().put("text", "after"));
            assertEquals(2, alice.readJson().getLong("seq"),
                    "a blob must not consume a number: a client counting seq to detect a gap would " +
                            "otherwise see one that nothing was lost in");
            assertEquals(2, bob.readJson().getLong("seq"));
            assertEquals(2, channels.get(CHANNEL).currentSeq());
        }
    }

    @Test
    public void aBlobCountsAsChannelActivity() throws Exception {
        startAnonymous(Collections.singletonList(binaryChannel(CHANNEL, Channel.Mode.ECHO)));

        try (WsTestClient alice = connect(); WsTestClient bob = connect()) {
            alice.handshake(connectPath(CHANNEL));
            alice.readParticipantsSnapshot();
            bob.handshake(connectPath(CHANNEL));
            bob.readParticipantsSnapshot();
            alice.readJson();//bob's join
            awaitParticipants(CHANNEL, 2);

            long beforeTheBlob = channels.get(CHANNEL).getLastActivityAt();
            //the clock this is measured against has a coarse enough resolution on some platforms
            //that a blob sent in the same millisecond would prove nothing
            Thread.sleep(20);

            alice.sendBinary(new byte[]{9});
            bob.readBinary();

            assertTrue(channels.get(CHANNEL).getLastActivityAt() > beforeTheBlob,
                    "a channel carrying nothing but blobs must not be reported as idle all along");
        }
    }

    @Test
    public void aStateChannelRelaysBlobsWithoutTouchingItsDocument() throws Exception {
        //the flag is orthogonal to the mode: a whiteboard with a shared document and pasted images
        //is one channel, not two
        startAnonymous(Collections.singletonList(new ChannelDefinition(CHANNEL, Channel.Mode.STATE,
                new JSONObject().put("title", "board"), null, false, true, true, null, null, true)));

        try (WsTestClient alice = connect(); WsTestClient bob = connect()) {
            alice.handshake(connectPath(CHANNEL));
            alice.readJson();//the state snapshot
            alice.readParticipantsSnapshot();
            bob.handshake(connectPath(CHANNEL));
            bob.readJson();
            bob.readParticipantsSnapshot();
            alice.readJson();//bob's join
            awaitParticipants(CHANNEL, 2);

            alice.sendBinary(new byte[]{4, 2});
            assertArrayEquals(new byte[]{4, 2}, bob.readBinary());

            //the document is untouched and still numbered from zero: the relay went past it
            assertEquals(0, channels.get(CHANNEL).currentSeq());
        }
    }

    @Test
    public void aChannelWithoutTheFlagRefusesABlobAndStaysUsable() throws Exception {
        startAnonymous(Collections.singletonList(new ChannelDefinition(CHANNEL)));

        try (WsTestClient alice = connect(); WsTestClient bob = connect()) {
            alice.handshake(connectPath(CHANNEL));
            alice.readParticipantsSnapshot();
            bob.handshake(connectPath(CHANNEL));
            bob.readParticipantsSnapshot();
            alice.readJson();//bob's join
            awaitParticipants(CHANNEL, 2);

            alice.sendBinary(new byte[]{1, 2, 3});

            JSONObject refusal = alice.readJson();
            assertFalse(refusal.getBoolean("ok"));
            assertTrue(refusal.isNull("id"), "a binary frame carries no request id to answer with");
            assertEquals(ChannelWebSocketHandler.ERROR_KIND_BAD_REQUEST,
                    refusal.getJSONObject("error").getString("kind"));

            //the connection survives the refusal, like it does for an unknown command
            alice.send(1, new JSONObject().put("text", "still here"));
            assertTrue(alice.readJson().getBoolean("ok"));
            assertEquals("still here", bob.readJson().getJSONObject("payload").getString("text"));
        }
    }

    @Test
    public void sendingABlobNeedsThePostRight() throws Exception {
        config.setAuthMode(SHTTPSConfig.AuthMode.BASIC_AUTH);
        AuthManager authManager = new BasicAuthManager(userStoreWith(
                userWith("listener", User.ChannelRights.CONNECT),
                userWith("speaker", User.ChannelRights.CONNECT, User.ChannelRights.POST)));
        start(Collections.singletonList(binaryChannel(CHANNEL, Channel.Mode.ECHO)), authManager,
                Collections.singletonList(new BasicAuthMiddleware(authManager)));

        try (WsTestClient listener = connect(); WsTestClient speaker = connect()) {
            listener.handshake(connectPath(CHANNEL), basicAuth("listener"));
            speaker.handshake(connectPath(CHANNEL), basicAuth("speaker"));
            awaitParticipants(CHANNEL, 2);

            listener.sendBinary(new byte[]{1});
            JSONObject refusal = listener.readJson();
            assertFalse(refusal.getBoolean("ok"));
            assertEquals(ChannelWebSocketHandler.ERROR_KIND_FORBIDDEN,
                    refusal.getJSONObject("error").getString("kind"));
            assertTrue(refusal.getJSONObject("error").getString("message").contains("POST"),
                    "the refusal has to name the missing right: " + refusal);

            //a read-only participant still receives what everybody else sends
            speaker.sendBinary(new byte[]{7});
            assertArrayEquals(new byte[]{7}, listener.readBinary());
        }
    }

    @Test
    public void aBlobOverTheConfiguredCapClosesTheConnection() throws Exception {
        //the cap belongs to the endpoint rather than the channel, and is enforced by the frame
        //reader before any of this handler's code runs - hence a close, not an error frame
        config.setChannelMaxMessageBytes(64);
        startAnonymous(Collections.singletonList(binaryChannel(CHANNEL, Channel.Mode.ECHO)));

        try (WsTestClient alice = connect()) {
            alice.handshake(connectPath(CHANNEL));
            alice.readParticipantsSnapshot();

            alice.sendBinary(new byte[200]);

            assertEquals(WebSocketCloseCodes.MESSAGE_TOO_BIG, alice.awaitCloseCode());
        }
    }

    @Test
    public void theCreateApiAcceptsTheFlagAndReportsItBack() throws Exception {
        config.setAllowDynamicChannelCreation(true);
        startAnonymous(Collections.emptyList());

        try (WsTestClient creator = connect()) {
            HttpAnswer answer = creator.post(ChannelsCollectionRequestHandler.PATH,
                    new JSONObject().put(ChannelDefinition.FIELD_ID, "created")
                            .put(ChannelDefinition.FIELD_BINARY_ALLOWED, true).toString(),
                    Collections.emptyMap());
            assertEquals("HTTP/1.1 201 Created", answer.statusLine);
            assertTrue(new JSONObject(answer.body).getBoolean(ChannelDefinition.FIELD_BINARY_ALLOWED),
                    "a channel has to report whether it carries blobs: " + answer.body);
        }

        Channel created = channels.get("created");
        assertNotNull(created);
        assertTrue(created.binaryAllowed);

        //and a channel created without the field is text-only, which is what everything created
        //before this feature existed was
        try (WsTestClient creator = connect()) {
            HttpAnswer answer = creator.post(ChannelsCollectionRequestHandler.PATH,
                    new JSONObject().put(ChannelDefinition.FIELD_ID, "plain").toString(),
                    Collections.emptyMap());
            assertEquals("HTTP/1.1 201 Created", answer.statusLine);
            assertFalse(new JSONObject(answer.body).getBoolean(ChannelDefinition.FIELD_BINARY_ALLOWED));
        }
    }

    @Test
    public void theFlagSurvivesConfigurationStorage() throws Exception {
        config.setPredefinedChannels(Collections.singletonList(
                binaryChannel(CHANNEL, Channel.Mode.ECHO)));

        List<ChannelDefinition> read = config.getPredefinedChannels();
        assertNotNull(read);
        assertEquals(1, read.size());
        assertTrue(read.get(0).binaryAllowed, "the flag has to survive the round trip through storage");
    }

    private static ChannelDefinition binaryChannel(String id, Channel.Mode mode) {
        return new ChannelDefinition(id, mode, null, null, false, true, true, null, null, true);
    }

    private static String connectPath(String channelId) {
        return ChannelsPathRequestHandler.PATH_PREFIX + channelId + "/" +
                ChannelsPathRequestHandler.ACTION_CONNECT;
    }

    /**
     * Waits for the channel to hold that many participants. Joining happens on the server's own
     * connection thread, after the handshake the test saw, so a broadcast that has to reach somebody
     * must not be sent before they are actually in the channel.
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
        if (server != null) {
            //the echoToSelf test starts a second server to check the other half of the setting
            server.stopListen();
        }
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
