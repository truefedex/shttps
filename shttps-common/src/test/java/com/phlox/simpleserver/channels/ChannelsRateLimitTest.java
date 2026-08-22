package com.phlox.simpleserver.channels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.phlox.server.SimpleHttpServer;
import com.phlox.server.handlers.router.Router;
import com.phlox.server.request.Request;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.DummyAuthManager;
import com.phlox.simpleserver.database.TestDBEnvironment;
import com.phlox.simpleserver.handlers.channels.ChannelWebSocketHandler;
import com.phlox.simpleserver.handlers.channels.ChannelsCollectionRequestHandler;
import com.phlox.simpleserver.handlers.channels.ChannelsPathRequestHandler;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The per-connection message limit of §2, which the HTTP {@code RateLimitingMiddleware} cannot
 * stand in for: that one sees the handshake as a single request and then never hears from the
 * connection again, however much it goes on to send.
 * <p>
 * A flood here is deliberately small and quick - a few dozen frames, sent as fast as they can be
 * written. That is far more than the limit under test and still finishes well inside the handler's
 * one-second close handshake timeout, so the server has not yet dropped the TCP connection and the
 * close frame this test is waiting for cannot be lost to a reset.
 */
@Timeout(30)
public class ChannelsRateLimitTest {
    private static final String CHANNEL = "lobby";
    private static final int LIMIT_PER_SECOND = 5;

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
    public void floodingPastTheLimitClosesTheConnectionWith4429() throws Exception {
        start();

        try (WsTestClient flooder = connect()) {
            flooder.handshake(connectPath());
            flooder.readParticipantsSnapshot();

            flood(flooder, 50);

            assertEquals(ChannelWebSocketHandler.CLOSE_CODE_TOO_MANY_REQUESTS,
                    flooder.awaitCloseCode(),
                    "a connection sending faster than the channel allows has to be told why it is going away");
        }
    }

    @Test
    public void theLimitIsPerConnectionAndNotPerChannel() throws Exception {
        start();

        try (WsTestClient flooder = connect(); WsTestClient neighbour = connect()) {
            flooder.handshake(connectPath());
            flooder.readParticipantsSnapshot();
            neighbour.handshake(connectPath());
            neighbour.readParticipantsSnapshot();

            flood(flooder, 50);
            assertEquals(ChannelWebSocketHandler.CLOSE_CODE_TOO_MANY_REQUESTS, flooder.awaitCloseCode());

            //the other participant shares the channel but not the bucket: it is still connected, and
            //what it sends is still accepted
            neighbour.send(1, new JSONObject().put("text", "still here"));
            JSONObject ack = awaitAnswer(neighbour);
            assertTrue(ack.getBoolean("ok"),
                    "one participant's flood must not have spent another participant's allowance");
            assertEquals(1, ack.getInt("id"));
        }
    }

    /**
     * The case the per-channel setting exists for: one channel carries a firehose while the server
     * as a whole stays protected. Zero is not "unset" - it is the answer.
     */
    @Test
    public void aChannelLimitOfZeroMeansNoLimitAtAllDespiteTheServerSetting() throws Exception {
        start(LIMIT_PER_SECOND, 0);

        try (WsTestClient flooder = connect()) {
            flooder.handshake(connectPath());
            flooder.readParticipantsSnapshot();

            flood(flooder, 50);

            //all fifty were accepted: the fiftieth is acknowledged like the first, and the
            //connection is still open to say so
            JSONObject ack = awaitAnswer(flooder, 49);
            assertTrue(ack.getBoolean("ok"));
            assertEquals(49, ack.getInt("id"),
                    "a channel that asked for no limit was throttled with the server default");
        }
    }

    @Test
    public void aChannelWithItsOwnLimitIsHeldToItAndNotToTheServerSetting() throws Exception {
        //the server would allow this flood comfortably; the channel is the one refusing it
        start(500, 3);

        try (WsTestClient flooder = connect()) {
            flooder.handshake(connectPath());
            flooder.readParticipantsSnapshot();

            flood(flooder, 50);

            assertEquals(ChannelWebSocketHandler.CLOSE_CODE_TOO_MANY_REQUESTS,
                    flooder.awaitCloseCode(),
                    "the channel's own limit has to be the one that counts");
        }
    }

    /**
     * Sends as fast as the socket takes it. A write can fail once the server has closed its end,
     * which is not a failure of the test - by then it has already sent more than enough.
     */
    private static void flood(WsTestClient client, int count) {
        for (int i = 0; i < count; i++) {
            try {
                client.send(i, new JSONObject().put("text", "flood"));
            } catch (IOException e) {
                return;
            }
        }
    }

    /** The next answer, past the events the flood delivered to this connection first. */
    private static JSONObject awaitAnswer(WsTestClient client) throws IOException {
        while (true) {
            JSONObject frame = client.readJson();
            if (frame.has("ok")) {
                return frame;
            }
        }
    }

    /** The answer to one particular request, past the answers to the ones before it. */
    private static JSONObject awaitAnswer(WsTestClient client, int requestId) throws IOException {
        while (true) {
            JSONObject answer = awaitAnswer(client);
            if (answer.optInt("id", -1) == requestId) {
                return answer;
            }
        }
    }

    private String connectPath() {
        return ChannelsPathRequestHandler.PATH_PREFIX + CHANNEL + "/" +
                ChannelsPathRequestHandler.ACTION_CONNECT;
    }

    /** A channel that leaves the limit to the server-wide setting. */
    private void start() throws IOException {
        start(LIMIT_PER_SECOND, null);
    }

    /**
     * @param serverLimit  the server-wide {@code channelMessageRateLimitPerSecond}
     * @param channelLimit this channel's own, or null to leave it to the server-wide one
     */
    private void start(int serverLimit, Integer channelLimit) throws IOException {
        config.setAuthMode(SHTTPSConfig.AuthMode.NONE);
        config.setChannelsEnabled(true);
        config.setChannelMessageRateLimitPerSecond(serverLimit);
        //presence is off here: the join and leave events of a second connection would land in the
        //middle of the frames these tests read, and they are ChannelsPresenceTest's subject
        config.setPredefinedChannels(Collections.singletonList(new ChannelDefinition(CHANNEL,
                Channel.Mode.ECHO, null, null, false, false, true, null, channelLimit)));

        AuthManager authManager = new DummyAuthManager();
        ChannelManager channelManager = new ChannelManager();
        channelManager.applyPredefinedChannels(config);

        ChannelWebSocketHandler webSocketHandler =
                new ChannelWebSocketHandler(channelManager, config, authManager);
        //keep the tests deterministic: no pings appearing in the middle of the expected frames
        webSocketHandler.options.idlePingIntervalMillis = 0;

        Router router = new Router(null, Collections.emptyList());
        router.addRoute(ChannelsCollectionRequestHandler.PATH,
                Set.of(Request.METHOD_GET, Request.METHOD_POST),
                new ChannelsCollectionRequestHandler(channelManager, config, authManager),
                Collections.emptyList());
        router.addRouteByPathPrefix(ChannelsPathRequestHandler.PATH_PREFIX,
                new ChannelsPathRequestHandler(channelManager, config, authManager, webSocketHandler),
                List.of());

        ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        port = serverSocket.getLocalPort();
        server = new SimpleHttpServer(router, null);
        server.startListen(serverSocket);
    }

    private WsTestClient connect() throws IOException {
        return new WsTestClient(port);
    }
}
