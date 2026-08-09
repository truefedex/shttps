package com.phlox.server.websocket;

import com.phlox.server.request.Request;
import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.server.utils.SHTTPSLoggerProxy.Logger;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One established WebSocket connection.
 * <p>
 * Reading happens on the connection's own thread inside {@link #runReadLoop(WebSocketListener)},
 * which dispatches the events to a listener and only returns when the connection is done.
 * Sending is thread safe and may be done from any thread, which is what makes server-initiated
 * pushes possible.
 */
public class WebSocketSession {
    private static final Logger logger = SHTTPSLoggerProxy.getLogger(WebSocketSession.class);

    private static final int MAX_CONTROL_FRAME_PAYLOAD_LENGTH = 125;
    private static final int MAX_CLOSE_REASON_LENGTH = MAX_CONTROL_FRAME_PAYLOAD_LENGTH - 2;

    /** The GET request that carried the handshake - headers, cookies, query, auth results. */
    @NotNull
    public final Request handshakeRequest;

    /** Sub-protocol agreed on during the handshake, or null if none was negotiated. */
    @Nullable
    public final String subProtocol;

    @NotNull
    public final WebSocketOptions options;

    private final Socket socket;
    private final WebSocketFrameReader reader;
    private final WebSocketFrameWriter writer;

    //free-form per-connection state for the application code
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    private final Object closeLock = new Object();
    private volatile boolean closeFrameSent = false;
    private volatile boolean closeFrameReceived = false;
    private volatile boolean closeStatusResolved = false;
    private volatile int closeCode = WebSocketCloseCodes.ABNORMAL_CLOSURE;
    private volatile String closeReason = "";
    private int unansweredPings = 0;

    public WebSocketSession(@NotNull Socket socket, @NotNull InputStream input, @NotNull OutputStream output,
                            @NotNull Request handshakeRequest, @Nullable String subProtocol,
                            @NotNull WebSocketOptions options) {
        this.socket = socket;
        this.handshakeRequest = handshakeRequest;
        this.subProtocol = subProtocol;
        this.options = options;
        this.reader = new WebSocketFrameReader(input, options.maxFramePayloadLength, true);
        this.writer = new WebSocketFrameWriter(output);
    }

    /**
     * Whether messages can still be sent: no close frame went in either direction yet and the
     * socket is alive.
     */
    public boolean isOpen() {
        return !closeFrameSent && !closeFrameReceived && !socket.isClosed();
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public Socket getSocket() {
        return socket;
    }

    public String getRemoteAddress() {
        return handshakeRequest.hostAddress;
    }

    /**
     * Status code the connection ended with. Meaningful once the connection is closed;
     * {@link WebSocketCloseCodes#ABNORMAL_CLOSURE} means there was no close handshake.
     */
    public int getCloseCode() {
        return closeCode;
    }

    public String getCloseReason() {
        return closeReason;
    }

    public void sendText(String message) throws IOException {
        byte[] payload = message.getBytes(StandardCharsets.UTF_8);
        sendMessage(WebSocketFrame.OPCODE_TEXT, payload, 0, payload.length);
    }

    public void sendBinary(byte[] message) throws IOException {
        sendMessage(WebSocketFrame.OPCODE_BINARY, message, 0, message.length);
    }

    public void sendBinary(byte[] message, int offset, int length) throws IOException {
        sendMessage(WebSocketFrame.OPCODE_BINARY, message, offset, length);
    }

    private void sendMessage(int opCode, byte[] payload, int offset, int length) throws IOException {
        checkOpenForSending();
        writer.writeMessage(opCode, payload, offset, length, options.outgoingFrameLength);
    }

    public void sendPing(byte[] payload) throws IOException {
        checkOpenForSending();
        checkControlPayloadLength(payload);
        writer.writeFrame(true, WebSocketFrame.OPCODE_PING, payload);
    }

    public void sendPong(byte[] payload) throws IOException {
        checkOpenForSending();
        checkControlPayloadLength(payload);
        writer.writeFrame(true, WebSocketFrame.OPCODE_PONG, payload);
    }

    /**
     * Starts the closing handshake with {@link WebSocketCloseCodes#NORMAL_CLOSURE}.
     */
    public void close() {
        close(WebSocketCloseCodes.NORMAL_CLOSURE, "");
    }

    /**
     * Starts the closing handshake: sends a close frame and lets the read loop wait for the
     * peer's answer (at most {@link WebSocketOptions#closeHandshakeTimeoutMillis}). Safe to call
     * from any thread and more than once, only the first call has an effect. Failures to write
     * the close frame are swallowed - the connection is going away anyway.
     */
    public void close(int code, String reason) {
        synchronized (closeLock) {
            if (closeFrameSent) {
                return;
            }
            closeFrameSent = true;
        }
        resolveCloseStatus(code, reason != null ? reason : "");

        try {
            writer.writeFrame(true, WebSocketFrame.OPCODE_CLOSE, buildClosePayload(code, reason));
        } catch (IOException e) {
            logger.d("Could not send the close frame: " + e.getMessage());
        }

        //do not sit here for the whole idle interval waiting for the peer's answer
        int closeTimeout = options.closeHandshakeTimeoutMillis;
        if (closeTimeout > 0) {
            try {
                socket.setSoTimeout(closeTimeout);
            } catch (SocketException e) {
                logger.d("Could not shorten the read timeout for the close handshake: " + e.getMessage());
            }
        }
    }

    /**
     * Reads frames until the connection ends, dispatching everything to the listener. Returns
     * normally when the connection was closed by either side.
     *
     * @throws WebSocketProtocolException if the peer violated the protocol (the close frame
     *                                    with the matching status code was already sent)
     * @throws IOException                on any other connection failure
     */
    public void runReadLoop(@NotNull WebSocketListener listener) throws IOException {
        applyIdleReadTimeout();

        ByteArrayOutputStream messageBuffer = null;
        int messageOpCode = -1;

        try {
            while (!closeFrameReceived) {
                WebSocketFrame frame;
                try {
                    frame = reader.readFrame();
                } catch (SocketTimeoutException e) {
                    if (closeFrameSent) {
                        //the peer never answered our close frame
                        break;
                    }
                    if (options.idlePingIntervalMillis <= 0) {
                        throw e;
                    }
                    if (unansweredPings >= Math.max(1, options.maxUnansweredPings)) {
                        resolveCloseStatus(WebSocketCloseCodes.ABNORMAL_CLOSURE, "Ping timeout");
                        logger.d("Closing a WebSocket connection that stopped answering pings");
                        break;
                    }
                    unansweredPings++;
                    writer.writeFrame(true, WebSocketFrame.OPCODE_PING, WebSocketFrame.EMPTY_PAYLOAD);
                    continue;
                }

                if (frame == null) {
                    //peer went away without a close handshake
                    resolveCloseStatus(WebSocketCloseCodes.ABNORMAL_CLOSURE, "Connection closed by peer");
                    break;
                }

                if (frame.opCode == WebSocketFrame.OPCODE_CLOSE) {
                    handleCloseFrame(frame);
                    break;
                }

                if (closeFrameSent) {
                    //we are closing: anything but the peer's close frame is of no interest now
                    continue;
                }

                switch (frame.opCode) {
                    case WebSocketFrame.OPCODE_PING:
                        sendPong(frame.payload);
                        if (!dispatchPing(listener, frame.payload)) {
                            return;
                        }
                        break;
                    case WebSocketFrame.OPCODE_PONG:
                        unansweredPings = 0;
                        if (!dispatchPong(listener, frame.payload)) {
                            return;
                        }
                        break;
                    case WebSocketFrame.OPCODE_TEXT:
                    case WebSocketFrame.OPCODE_BINARY:
                        if (messageOpCode != -1) {
                            throw new WebSocketProtocolException(WebSocketCloseCodes.PROTOCOL_ERROR,
                                    "New message started while the previous one is not finished");
                        }
                        checkMessageLength(frame.payload.length);
                        if (frame.fin) {
                            if (!dispatchMessage(listener, frame.opCode, frame.payload)) {
                                return;
                            }
                        } else {
                            messageOpCode = frame.opCode;
                            messageBuffer = new ByteArrayOutputStream(frame.payload.length);
                            messageBuffer.write(frame.payload, 0, frame.payload.length);
                        }
                        break;
                    case WebSocketFrame.OPCODE_CONTINUATION:
                        if (messageOpCode == -1) {
                            throw new WebSocketProtocolException(WebSocketCloseCodes.PROTOCOL_ERROR,
                                    "Continuation frame without a message to continue");
                        }
                        checkMessageLength((long) messageBuffer.size() + frame.payload.length);
                        messageBuffer.write(frame.payload, 0, frame.payload.length);
                        if (frame.fin) {
                            byte[] message = messageBuffer.toByteArray();
                            int completedOpCode = messageOpCode;
                            messageOpCode = -1;
                            messageBuffer = null;
                            if (!dispatchMessage(listener, completedOpCode, message)) {
                                return;
                            }
                        }
                        break;
                    default:
                        throw new WebSocketProtocolException(WebSocketCloseCodes.PROTOCOL_ERROR,
                                "Unexpected frame opcode: 0x" + Integer.toHexString(frame.opCode));
                }
            }
        } catch (WebSocketProtocolException e) {
            close(e.closeCode, e.getMessage());
            throw e;
        }
    }

    private void handleCloseFrame(WebSocketFrame frame) throws IOException {
        closeFrameReceived = true;

        int code = WebSocketCloseCodes.NO_STATUS_RECEIVED;
        String reason = "";
        byte[] payload = frame.payload;
        if (payload.length == 1) {
            throw new WebSocketProtocolException(WebSocketCloseCodes.PROTOCOL_ERROR,
                    "Close frame payload with a truncated status code");
        }
        if (payload.length >= 2) {
            code = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
            if (!WebSocketCloseCodes.isAllowedOnWire(code)) {
                throw new WebSocketProtocolException(WebSocketCloseCodes.PROTOCOL_ERROR,
                        "Close frame with a reserved or invalid status code: " + code);
            }
            reason = decodeUtf8(payload, 2, payload.length - 2);
        }

        //the peer's status wins over anything we may have chosen for ourselves
        closeCode = code;
        closeReason = reason;
        closeStatusResolved = true;

        //answer the close frame, echoing the status code back as required by RFC 6455
        synchronized (closeLock) {
            if (closeFrameSent) {
                return;
            }
            closeFrameSent = true;
        }
        try {
            writer.writeFrame(true, WebSocketFrame.OPCODE_CLOSE, buildClosePayload(code, reason));
        } catch (IOException e) {
            logger.d("Could not echo the close frame: " + e.getMessage());
        }
    }

    /**
     * @return false if the listener failed and the connection was closed because of it
     */
    private boolean dispatchMessage(WebSocketListener listener, int opCode, byte[] payload) throws IOException {
        if (opCode == WebSocketFrame.OPCODE_TEXT) {
            String text = decodeUtf8(payload, 0, payload.length);
            try {
                listener.onTextMessage(this, text);
            } catch (Throwable t) {
                return handleListenerFailure(listener, t);
            }
        } else {
            try {
                listener.onBinaryMessage(this, payload);
            } catch (Throwable t) {
                return handleListenerFailure(listener, t);
            }
        }
        return true;
    }

    private boolean dispatchPing(WebSocketListener listener, byte[] payload) {
        try {
            listener.onPing(this, payload);
        } catch (Throwable t) {
            return handleListenerFailure(listener, t);
        }
        return true;
    }

    private boolean dispatchPong(WebSocketListener listener, byte[] payload) {
        try {
            listener.onPong(this, payload);
        } catch (Throwable t) {
            return handleListenerFailure(listener, t);
        }
        return true;
    }

    private boolean handleListenerFailure(WebSocketListener listener, Throwable error) {
        logger.stackTrace(error);
        try {
            listener.onError(this, error);
        } catch (Throwable ignored) {
        }
        close(WebSocketCloseCodes.INTERNAL_SERVER_ERROR, "Internal server error");
        return false;
    }

    private void checkMessageLength(long length) throws WebSocketProtocolException {
        if (length > options.maxMessagePayloadLength) {
            throw new WebSocketProtocolException(WebSocketCloseCodes.MESSAGE_TOO_BIG,
                    "Message longer than the limit of " + options.maxMessagePayloadLength + " bytes");
        }
    }

    private void checkOpenForSending() throws IOException {
        if (closeFrameSent || closeFrameReceived) {
            throw new IOException("WebSocket connection is closed");
        }
    }

    private static void checkControlPayloadLength(byte[] payload) {
        if (payload.length > MAX_CONTROL_FRAME_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("Control frame payload can not be longer than " +
                    MAX_CONTROL_FRAME_PAYLOAD_LENGTH + " bytes");
        }
    }

    private void applyIdleReadTimeout() throws SocketException {
        //a WebSocket connection is idle by nature, the HTTP keep-alive timeout does not apply
        socket.setSoTimeout(Math.max(options.idlePingIntervalMillis, 0));
    }

    private void resolveCloseStatus(int code, String reason) {
        if (closeStatusResolved) {
            return;
        }
        closeCode = code;
        closeReason = reason;
        closeStatusResolved = true;
    }

    /**
     * Text data must be valid UTF-8, invalid sequences are a protocol error and not something
     * to silently replace with the substitution character.
     */
    private static String decodeUtf8(byte[] bytes, int offset, int length) throws WebSocketProtocolException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes, offset, length)).toString();
        } catch (CharacterCodingException e) {
            throw new WebSocketProtocolException(WebSocketCloseCodes.INVALID_FRAME_PAYLOAD_DATA,
                    "Text payload is not valid UTF-8");
        }
    }

    private static byte[] buildClosePayload(int code, String reason) {
        if (code == WebSocketCloseCodes.NO_STATUS_RECEIVED || code == WebSocketCloseCodes.ABNORMAL_CLOSURE ||
                code == WebSocketCloseCodes.TLS_HANDSHAKE_FAILURE) {
            //these are local-only codes, they must never be put on the wire
            return WebSocketFrame.EMPTY_PAYLOAD;
        }
        byte[] reasonBytes = reason == null || reason.isEmpty() ?
                WebSocketFrame.EMPTY_PAYLOAD : reason.getBytes(StandardCharsets.UTF_8);
        int reasonLength = Math.min(reasonBytes.length, MAX_CLOSE_REASON_LENGTH);
        if (reasonLength < reasonBytes.length) {
            //do not cut a multi-byte character in half, the peer validates the reason as UTF-8
            while (reasonLength > 0 && (reasonBytes[reasonLength] & 0xC0) == 0x80) {
                reasonLength--;
            }
        }
        byte[] payload = new byte[2 + reasonLength];
        payload[0] = (byte) (code >>> 8);
        payload[1] = (byte) code;
        System.arraycopy(reasonBytes, 0, payload, 2, reasonLength);
        return payload;
    }
}
