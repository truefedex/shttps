package com.phlox.simpleserver.channels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.phlox.server.request.Request;
import com.phlox.server.websocket.WebSocketFrame;
import com.phlox.server.websocket.WebSocketFrameReader;
import com.phlox.simpleserver.handlers.channels.ChannelWebSocketHandler;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * A minimal WebSocket client shared by the channel tests: it masks what it sends and reads server
 * frames with the same reader the server uses, configured for the other direction. Deliberately raw
 * sockets rather than {@code java.net.http.WebSocket} - a rejected handshake has to be inspectable
 * as the HTTP response it is, and the same connection then serves the plain HTTP calls of the
 * management API.
 */
class WsTestClient implements Closeable {
    static final String STATUS_LINE = "";
    private static final String HANDSHAKE_KEY = "dGhlIHNhbXBsZSBub25jZQ==";
    private static final byte[] MASK = {0x1a, 0x2b, 0x3c, 0x4d};

    private final Socket socket;
    private final OutputStream output;
    private final InputStream input;
    private final WebSocketFrameReader reader;

    WsTestClient(int port) throws IOException {
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

    HttpAnswer get(String path, Map<String, String> extraHeaders) throws IOException {
        return request(Request.METHOD_GET, path, null, extraHeaders);
    }

    HttpAnswer post(String path, String body, Map<String, String> extraHeaders) throws IOException {
        return request(Request.METHOD_POST, path, body, extraHeaders);
    }

    HttpAnswer delete(String path, Map<String, String> extraHeaders) throws IOException {
        return request(Request.METHOD_DELETE, path, null, extraHeaders);
    }

    /**
     * An ordinary HTTP request on the same connection style: status line, headers and body. A body
     * is always sent with its {@code Content-Length} - without one the server has no request body
     * to read, and a handler expecting JSON answers a puzzling 400.
     */
    HttpAnswer request(String method, String path, String body, Map<String, String> extraHeaders)
            throws IOException {
        byte[] payload = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
        StringBuilder request = new StringBuilder()
                .append(method).append(" ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: localhost\r\n");
        if (payload != null) {
            request.append("Content-Type: application/json\r\n")
                    .append("Content-Length: ").append(payload.length).append("\r\n");
        }
        for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
            request.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        request.append("\r\n");
        output.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (payload != null) {
            output.write(payload);
        }
        output.flush();

        Map<String, String> headers = readHeaders();
        String lengthHeader = headers.get("content-length");
        String answerBody = lengthHeader == null ? "" : readBody(Integer.parseInt(lengthHeader));
        return new HttpAnswer(headers.get(STATUS_LINE), answerBody);
    }

    private String readBody(int length) throws IOException {
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(body, offset, length - offset);
            if (count == -1) break;
            offset += count;
        }
        return new String(body, 0, offset, StandardCharsets.UTF_8);
    }

    Map<String, String> handshake(String path, Map<String, String> extraHeaders) throws IOException {
        Map<String, String> response = handshakeRaw(path, extraHeaders);
        assertEquals("HTTP/1.1 101 Switching Protocols", response.get(STATUS_LINE));
        return response;
    }

    Map<String, String> handshakeRaw(String path) throws IOException {
        return handshakeRaw(path, Collections.emptyMap());
    }

    Map<String, String> handshakeRaw(String path, Map<String, String> extraHeaders) throws IOException {
        StringBuilder request = new StringBuilder()
                .append("GET ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: localhost\r\n")
                .append("Upgrade: websocket\r\n")
                .append("Connection: Upgrade\r\n")
                .append("Sec-WebSocket-Key: ").append(HANDSHAKE_KEY).append("\r\n")
                .append("Sec-WebSocket-Version: 13\r\n");
        for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
            request.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        request.append("\r\n");
        output.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
        output.flush();
        return readHeaders();
    }

    void send(int id, JSONObject payload) throws IOException {
        JSONObject envelope = new JSONObject();
        envelope.put("id", id);
        envelope.put("command", ChannelWebSocketHandler.COMMAND_SEND);
        envelope.put("payload", payload);
        sendEnvelope(envelope);
    }

    /** Any command frame, for the STATE commands that carry a path rather than a payload. */
    void sendEnvelope(JSONObject envelope) throws IOException {
        sendFrame(true, WebSocketFrame.OPCODE_TEXT,
                envelope.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** A raw binary message, for the channels that relay blobs (§7.1). */
    void sendBinary(byte[] payload) throws IOException {
        sendFrame(true, WebSocketFrame.OPCODE_BINARY, payload);
    }

    /** The next text frame, skipping any control frame that happens to arrive first. */
    JSONObject readJson() throws IOException {
        while (true) {
            WebSocketFrame frame = reader.readFrame();
            assertNotNull(frame, "connection closed while a frame was expected");
            if (frame.opCode == WebSocketFrame.OPCODE_TEXT) {
                return new JSONObject(new String(frame.payload, StandardCharsets.UTF_8));
            }
            assertFalse(frame.opCode == WebSocketFrame.OPCODE_CLOSE,
                    "the server closed the connection while a message was expected");
        }
    }

    /**
     * The next binary <i>message</i>, reassembled from its fragments. Anything above
     * {@code options.outgoingFrameLength} leaves the server split across a BINARY frame and its
     * continuations, which is the normal shape for a payload big enough to be worth sending as
     * binary at all - reading only the first frame would silently truncate it.
     * <p>
     * A text frame arriving instead is a failure rather than something to skip past: on a channel
     * relaying blobs the text frames are the errors, and swallowing one would turn a refusal into a
     * timeout.
     */
    byte[] readBinary() throws IOException {
        ByteArrayOutputStream message = null;
        while (true) {
            WebSocketFrame frame = reader.readFrame();
            assertNotNull(frame, "connection closed while a binary frame was expected");
            if (frame.opCode == WebSocketFrame.OPCODE_BINARY ||
                    frame.opCode == WebSocketFrame.OPCODE_CONTINUATION) {
                if (frame.fin && message == null) {
                    return frame.payload;//the common case: one frame, one message
                }
                if (message == null) {
                    message = new ByteArrayOutputStream(frame.payload.length * 2);
                }
                message.write(frame.payload, 0, frame.payload.length);
                if (frame.fin) {
                    return message.toByteArray();
                }
                continue;
            }
            assertFalse(frame.opCode == WebSocketFrame.OPCODE_TEXT,
                    "expected a binary message, got a text one: " +
                            new String(frame.payload, StandardCharsets.UTF_8));
            assertFalse(frame.opCode == WebSocketFrame.OPCODE_CLOSE,
                    "the server closed the connection while a binary message was expected");
        }
    }

    /**
     * Consumes the participant list a connection holding {@code LIST_PARTICIPANTS} is greeted with
     * (§9), and returns it. Every test that reads frames from such a connection has to account for
     * it explicitly - it is the first thing the server says, ahead of anything the test asked for.
     */
    JSONObject readParticipantsSnapshot() throws IOException {
        JSONObject frame = readJson();
        assertEquals(ChannelWebSocketHandler.EVENT_TYPE_PARTICIPANTS, frame.optString("type"),
                "expected the participant list a connection is greeted with, got: " + frame);
        return frame;
    }

    /**
     * Reads until the server closes, and returns the code it closed with - the counterpart of
     * {@link #readJson()} for the tests where the close <i>is</i> the expected answer.
     */
    int awaitCloseCode() throws IOException {
        while (true) {
            WebSocketFrame frame = reader.readFrame();
            assertNotNull(frame, "the connection ended without a close frame");
            if (frame.opCode == WebSocketFrame.OPCODE_CLOSE) {
                assertTrue(frame.payload.length >= 2, "close frame without a status code");
                return ((frame.payload[0] & 0xFF) << 8) | (frame.payload[1] & 0xFF);
            }
        }
    }

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
            if (b == '\r') continue;
            if (b == '\n') break;
            line.write(b);
        }
        return line.toString("ISO-8859-1");
    }

    private void sendFrame(boolean fin, int opCode, byte[] payload) throws IOException {
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
            //the 64 bit length, for the blobs a binary channel exists to carry. The top four bytes
            //stay zero: a Java array cannot be longer than 2^31-1
            frame.write(0x80 | 127);
            for (int i = 0; i < 4; i++) {
                frame.write(0);
            }
            frame.write(length >>> 24);
            frame.write(length >>> 16);
            frame.write(length >>> 8);
            frame.write(length);
        }
        frame.write(MASK, 0, MASK.length);
        for (int i = 0; i < length; i++) {
            frame.write(payload[i] ^ MASK[i & 3]);
        }
        output.write(frame.toByteArray());
        output.flush();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
