package com.phlox.server.websocket;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.phlox.server.SimpleHttpServer;
import com.phlox.server.handlers.router.Router;
import com.phlox.server.request.Request;
import com.phlox.server.responses.StandardResponses;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End to end checks over a real socket: handshake, framing and the connection takeover done by
 * {@link SimpleHttpServer}.
 */
@Timeout(30)
public class WebSocketServerIntegrationTest {
    private static final String HANDSHAKE_KEY = "dGhlIHNhbXBsZSBub25jZQ==";
    private static final String HANDSHAKE_ACCEPT = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";

    private SimpleHttpServer server;
    private int port;

    @AfterEach
    public void stopServer() {
        if (server != null) {
            server.stopListen();
            server = null;
        }
    }

    @Test
    public void echoesTextMessagesAndClosesGracefully() throws Exception {
        start(echoHandler());

        try (TestClient client = connect()) {
            Map<String, String> handshake = client.handshake("/ws");
            assertEquals("HTTP/1.1 101 Switching Protocols", handshake.get(TestClient.STATUS_LINE));
            assertEquals("websocket", handshake.get("upgrade"));
            assertEquals("Upgrade", handshake.get("connection"));
            assertEquals(HANDSHAKE_ACCEPT, handshake.get("sec-websocket-accept"));
            //the upgrade response must not be framed like an HTTP message anymore
            assertNull(handshake.get("content-length"));
            assertNull(handshake.get("keep-alive"));

            client.sendText("hello");
            WebSocketFrame echo = client.readFrame();
            assertEquals(WebSocketFrame.OPCODE_TEXT, echo.opCode);
            assertTrue(echo.fin);
            assertEquals("hello", new String(echo.payload, StandardCharsets.UTF_8));

            client.sendClose(WebSocketCloseCodes.NORMAL_CLOSURE, "bye");
            WebSocketFrame close = client.readFrame();
            assertEquals(WebSocketFrame.OPCODE_CLOSE, close.opCode);
            assertEquals(WebSocketCloseCodes.NORMAL_CLOSURE, closeCodeOf(close));
            //the server closes the connection right after the closing handshake
            assertNull(client.readFrame());
        }
    }

    @Test
    public void echoesBinaryAndReassemblesFragments() throws Exception {
        start(echoHandler());

        try (TestClient client = connect()) {
            client.handshake("/ws");

            client.sendFrame(false, WebSocketFrame.OPCODE_TEXT, "frag".getBytes(StandardCharsets.UTF_8));
            client.sendFrame(false, WebSocketFrame.OPCODE_CONTINUATION, "men".getBytes(StandardCharsets.UTF_8));
            client.sendFrame(true, WebSocketFrame.OPCODE_CONTINUATION, "ted".getBytes(StandardCharsets.UTF_8));
            assertEquals("fragmented", new String(client.readFrame().payload, StandardCharsets.UTF_8));

            byte[] binary = new byte[3000];
            for (int i = 0; i < binary.length; i++) {
                binary[i] = (byte) i;
            }
            client.sendFrame(true, WebSocketFrame.OPCODE_BINARY, binary);
            WebSocketFrame echo = client.readFrame();
            assertEquals(WebSocketFrame.OPCODE_BINARY, echo.opCode);
            assertArrayEquals(binary, echo.payload);
        }
    }

    @Test
    public void answersPingWithPong() throws Exception {
        start(echoHandler());

        try (TestClient client = connect()) {
            client.handshake("/ws");

            byte[] payload = "are you there".getBytes(StandardCharsets.UTF_8);
            client.sendFrame(true, WebSocketFrame.OPCODE_PING, payload);

            WebSocketFrame pong = client.readFrame();
            assertEquals(WebSocketFrame.OPCODE_PONG, pong.opCode);
            assertArrayEquals(payload, pong.payload);
        }
    }

    @Test
    public void serverCanPushAndCloseOnItsOwn() throws Exception {
        start(new WebSocketRequestHandler() {
            @Override
            public void onOpen(WebSocketSession session) throws Exception {
                session.sendText("welcome");
                session.close(WebSocketCloseCodes.GOING_AWAY, "done here");
            }
        });

        try (TestClient client = connect()) {
            client.handshake("/ws");

            assertEquals("welcome", new String(client.readFrame().payload, StandardCharsets.UTF_8));

            WebSocketFrame close = client.readFrame();
            assertEquals(WebSocketFrame.OPCODE_CLOSE, close.opCode);
            assertEquals(WebSocketCloseCodes.GOING_AWAY, closeCodeOf(close));
            assertEquals("done here", new String(close.payload, 2, close.payload.length - 2,
                    StandardCharsets.UTF_8));
        }
    }

    @Test
    public void reportsOpenAndCloseExactlyOnce() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        AtomicReference<String> closeStatus = new AtomicReference<>();
        CountDownLatch closeReported = new CountDownLatch(1);

        start(new WebSocketRequestHandler() {
            @Override
            public void onOpen(WebSocketSession session) {
                opened.incrementAndGet();
            }

            @Override
            public void onClose(WebSocketSession session, int code, String reason) {
                closed.incrementAndGet();
                closeStatus.set(code + ":" + reason);
                closeReported.countDown();
            }
        });

        try (TestClient client = connect()) {
            client.handshake("/ws");
            client.sendClose(WebSocketCloseCodes.NORMAL_CLOSURE, "client is done");
            client.readFrame();
        }

        assertTrue(closeReported.await(10, TimeUnit.SECONDS), "onClose was not called");
        assertEquals(1, opened.get());
        assertEquals(1, closed.get());
        //the status the peer sent is what the application sees
        assertEquals(WebSocketCloseCodes.NORMAL_CLOSURE + ":client is done", closeStatus.get());
    }

    @Test
    public void closesConnectionOnMessageOverTheLimit() throws Exception {
        WebSocketRequestHandler handler = echoHandler();
        handler.options.maxMessagePayloadLength = 10;
        start(handler);

        try (TestClient client = connect()) {
            client.handshake("/ws");
            client.sendText("way too long to be accepted");

            WebSocketFrame close = client.readFrame();
            assertEquals(WebSocketFrame.OPCODE_CLOSE, close.opCode);
            assertEquals(WebSocketCloseCodes.MESSAGE_TOO_BIG, closeCodeOf(close));
        }
    }

    @Test
    public void closesConnectionOnUnmaskedClientFrame() throws Exception {
        start(echoHandler());

        try (TestClient client = connect()) {
            client.handshake("/ws");
            //a client frame without the mask bit is a protocol violation
            client.rawWrite(new byte[]{(byte) 0x81, 0x02, 'h', 'i'});

            WebSocketFrame close = client.readFrame();
            assertEquals(WebSocketFrame.OPCODE_CLOSE, close.opCode);
            assertEquals(WebSocketCloseCodes.PROTOCOL_ERROR, closeCodeOf(close));
        }
    }

    @Test
    public void closesConnectionOnInvalidUtf8TextMessage() throws Exception {
        start(echoHandler());

        try (TestClient client = connect()) {
            client.handshake("/ws");
            client.sendFrame(true, WebSocketFrame.OPCODE_TEXT, new byte[]{(byte) 0xC3, 0x28});

            WebSocketFrame close = client.readFrame();
            assertEquals(WebSocketCloseCodes.INVALID_FRAME_PAYLOAD_DATA, closeCodeOf(close));
        }
    }

    @Test
    public void plainHttpRequestsStillWorkOnTheSameServer() throws Exception {
        Router router = new Router(null, Collections.emptyList());
        router.addRoute("/ws", Collections.singleton(Request.METHOD_GET), echoHandler());
        router.addRoute("/plain", Collections.singleton(Request.METHOD_GET),
                (context, request) -> StandardResponses.OK("still http"));
        startServer(router);

        try (TestClient client = connect()) {
            Map<String, String> response = client.request("/plain");
            assertEquals("HTTP/1.1 200 Ok", response.get(TestClient.STATUS_LINE));
            assertEquals("10", response.get("content-length"));
            assertEquals("Keep-Alive", response.get("connection"));
            assertEquals("still http", client.readBody(10));
        }
    }

    @Test
    public void handshakeGoesThroughTheRouteMiddlewares() throws Exception {
        Router router = new Router(null, Collections.emptyList());
        router.addRoute("/ws", Collections.singleton(Request.METHOD_GET), echoHandler(),
                Collections.singletonList((context, request, chain) -> {
                    if (!"secret".equals(request.queryParams.get("token"))) {
                        return StandardResponses.UNAUTHORIZED("no token");
                    }
                    return chain.proceed(context, request);
                }));
        startServer(router);

        try (TestClient client = connect()) {
            assertEquals("HTTP/1.1 401 Unauthorized", client.handshakeRaw("/ws").get(TestClient.STATUS_LINE));
        }
        try (TestClient client = connect()) {
            assertEquals("HTTP/1.1 101 Switching Protocols",
                    client.handshakeRaw("/ws?token=secret").get(TestClient.STATUS_LINE));
            client.sendText("hi");
            assertEquals("hi", new String(client.readFrame().payload, StandardCharsets.UTF_8));
        }
    }

    private static WebSocketRequestHandler echoHandler() {
        WebSocketRequestHandler handler = new WebSocketRequestHandler() {
            @Override
            public void onTextMessage(WebSocketSession session, String message) throws Exception {
                session.sendText(message);
            }

            @Override
            public void onBinaryMessage(WebSocketSession session, byte[] message) throws Exception {
                session.sendBinary(message);
            }
        };
        //keep the tests deterministic: no pings appearing in the middle of the expected frames
        handler.options.idlePingIntervalMillis = 0;
        return handler;
    }

    private void start(WebSocketRequestHandler handler) throws IOException {
        Router router = new Router(null, Collections.emptyList());
        router.addRoute("/ws", Collections.singleton(Request.METHOD_GET), handler);
        startServer(router);
    }

    private void startServer(Router router) throws IOException {
        ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        port = serverSocket.getLocalPort();
        server = new SimpleHttpServer(router, null);
        server.startListen(serverSocket);
    }

    private TestClient connect() throws IOException {
        return new TestClient(port);
    }

    private static int closeCodeOf(WebSocketFrame closeFrame) {
        assertTrue(closeFrame.payload.length >= 2, "close frame without a status code");
        return ((closeFrame.payload[0] & 0xFF) << 8) | (closeFrame.payload[1] & 0xFF);
    }

    /**
     * A minimal WebSocket client: masks what it sends and reads server frames with the same
     * reader the server uses, configured for the other direction.
     */
    private static class TestClient implements Closeable {
        static final String STATUS_LINE = "";
        private static final byte[] MASK = {0x1a, 0x2b, 0x3c, 0x4d};

        private final Socket socket;
        private final OutputStream output;
        private final InputStream input;
        private final WebSocketFrameReader reader;

        TestClient(int port) throws IOException {
            socket = new Socket(InetAddress.getLoopbackAddress(), port);
            socket.setSoTimeout(10000);
            output = socket.getOutputStream();
            input = socket.getInputStream();
            reader = new WebSocketFrameReader(input, 1024 * 1024, false);
        }

        Map<String, String> handshake(String path) throws IOException {
            Map<String, String> response = handshakeRaw(path);
            assertEquals("HTTP/1.1 101 Switching Protocols", response.get(STATUS_LINE));
            return response;
        }

        Map<String, String> handshakeRaw(String path) throws IOException {
            return sendRequest("GET " + path + " HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: " + HANDSHAKE_KEY + "\r\n" +
                    "Sec-WebSocket-Version: 13\r\n\r\n");
        }

        Map<String, String> request(String path) throws IOException {
            return sendRequest("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n");
        }

        private Map<String, String> sendRequest(String requestText) throws IOException {
            output.write(requestText.getBytes(StandardCharsets.ISO_8859_1));
            output.flush();
            return readHeaders();
        }

        /**
         * Reads the status line and headers byte by byte, so that whatever follows them stays
         * in the stream for the frame reader.
         */
        private Map<String, String> readHeaders() throws IOException {
            Map<String, String> headers = new HashMap<>();
            headers.put(STATUS_LINE, readLine());
            String line;
            while (!(line = readLine()).isEmpty()) {
                int separator = line.indexOf(':');
                headers.put(line.substring(0, separator).trim().toLowerCase(),
                        line.substring(separator + 1).trim());
            }
            return headers;
        }

        private String readLine() throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int b;
            while ((b = input.read()) != -1) {
                if (b == '\r') {
                    continue;
                }
                if (b == '\n') {
                    break;
                }
                line.write(b);
            }
            return line.toString("ISO-8859-1");
        }

        String readBody(int length) throws IOException {
            byte[] body = new byte[length];
            int offset = 0;
            while (offset < length) {
                int count = input.read(body, offset, length - offset);
                if (count == -1) {
                    break;
                }
                offset += count;
            }
            return new String(body, 0, offset, StandardCharsets.UTF_8);
        }

        void sendText(String message) throws IOException {
            sendFrame(true, WebSocketFrame.OPCODE_TEXT, message.getBytes(StandardCharsets.UTF_8));
        }

        void sendClose(int code, String reason) throws IOException {
            byte[] reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
            byte[] payload = new byte[2 + reasonBytes.length];
            payload[0] = (byte) (code >>> 8);
            payload[1] = (byte) code;
            System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);
            sendFrame(true, WebSocketFrame.OPCODE_CLOSE, payload);
        }

        void sendFrame(boolean fin, int opCode, byte[] payload) throws IOException {
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write((fin ? 0x80 : 0x00) | (opCode & 0x0F));
            int length = payload.length;
            if (length < 126) {
                frame.write(0x80 | length);
            } else if (length <= 0xFFFF) {
                frame.write(0x80 | 126);
                frame.write(length >>> 8);
                frame.write(length);
            } else {
                frame.write(0x80 | 127);
                for (int shift = 56; shift >= 0; shift -= 8) {
                    frame.write((int) ((long) length >>> shift));
                }
            }
            frame.write(MASK, 0, MASK.length);
            for (int i = 0; i < length; i++) {
                frame.write(payload[i] ^ MASK[i & 3]);
            }
            rawWrite(frame.toByteArray());
        }

        void rawWrite(byte[] bytes) throws IOException {
            output.write(bytes);
            output.flush();
        }

        WebSocketFrame readFrame() throws IOException {
            return reader.readFrame();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
