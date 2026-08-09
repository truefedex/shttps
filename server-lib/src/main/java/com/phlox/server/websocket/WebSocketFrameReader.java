package com.phlox.server.websocket;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;

/**
 * Reads RFC 6455 frames from a connection stream and validates them.
 * <p>
 * Not thread safe: a WebSocket connection is read by exactly one thread.
 */
public class WebSocketFrameReader {
    private final InputStream input;
    private final long maxFramePayloadLength;
    private final boolean requireMaskedFrames;

    /**
     * @param requireMaskedFrames true when reading frames sent by a client (a server must
     *                            reject unmasked client frames), false when reading server frames
     */
    public WebSocketFrameReader(InputStream input, long maxFramePayloadLength, boolean requireMaskedFrames) {
        this.input = input;
        this.maxFramePayloadLength = maxFramePayloadLength;
        this.requireMaskedFrames = requireMaskedFrames;
    }

    /**
     * Reads the next frame, blocking until it is fully available.
     *
     * @return the frame, or null if the peer closed the connection between frames
     * @throws SocketTimeoutException if the read timeout expired while waiting for a new frame
     *                                to start. A timeout in the middle of a frame is reported as
     *                                a plain {@link IOException} instead, because the stream
     *                                position is lost at that point and the connection is unusable
     * @throws WebSocketProtocolException if the peer violated the framing rules
     */
    public WebSocketFrame readFrame() throws IOException {
        //the only read allowed to time out: nothing of the frame has been consumed yet
        int firstByte = input.read();
        if (firstByte == -1) {
            return null;
        }

        boolean fin = (firstByte & 0x80) != 0;
        if ((firstByte & 0x70) != 0) {
            //RSV1-3 are only meaningful with a negotiated extension, and we negotiate none
            throw new WebSocketProtocolException(WebSocketCloseCodes.PROTOCOL_ERROR,
                    "Reserved frame bits are set but no extension was negotiated");
        }
        int opCode = firstByte & 0x0F;
        if (!WebSocketFrame.isKnownOpCode(opCode)) {
            throw new WebSocketProtocolException(WebSocketCloseCodes.PROTOCOL_ERROR,
                    "Unknown frame opcode: 0x" + Integer.toHexString(opCode));
        }

        int secondByte = readByte();
        boolean masked = (secondByte & 0x80) != 0;
        long payloadLength = secondByte & 0x7F;
        if (payloadLength == 126) {
            payloadLength = ((long) readByte() << 8) | readByte();
            if (payloadLength < 126) {
                throw new WebSocketProtocolException(WebSocketCloseCodes.PROTOCOL_ERROR,
                        "Payload length is not encoded in the shortest possible form");
            }
        } else if (payloadLength == 127) {
            payloadLength = 0;
            for (int i = 0; i < 8; i++) {
                payloadLength = (payloadLength << 8) | readByte();
            }
            if (payloadLength < 0) {
                throw new WebSocketProtocolException(WebSocketCloseCodes.PROTOCOL_ERROR,
                        "Payload length has the most significant bit set");
            }
            if (payloadLength <= 0xFFFF) {
                throw new WebSocketProtocolException(WebSocketCloseCodes.PROTOCOL_ERROR,
                        "Payload length is not encoded in the shortest possible form");
            }
        }

        if (WebSocketFrame.isControlOpCode(opCode)) {
            if (!fin) {
                throw new WebSocketProtocolException(WebSocketCloseCodes.PROTOCOL_ERROR,
                        "Control frames must not be fragmented");
            }
            if (payloadLength > 125) {
                throw new WebSocketProtocolException(WebSocketCloseCodes.PROTOCOL_ERROR,
                        "Control frame payload is longer than 125 bytes");
            }
        }

        if (requireMaskedFrames && !masked) {
            throw new WebSocketProtocolException(WebSocketCloseCodes.PROTOCOL_ERROR,
                    "Client frames must be masked");
        }
        if (!requireMaskedFrames && masked) {
            throw new WebSocketProtocolException(WebSocketCloseCodes.PROTOCOL_ERROR,
                    "Server frames must not be masked");
        }

        if (payloadLength > maxFramePayloadLength) {
            throw new WebSocketProtocolException(WebSocketCloseCodes.MESSAGE_TOO_BIG,
                    "Frame payload of " + payloadLength + " bytes exceeds the limit of " +
                            maxFramePayloadLength + " bytes");
        }

        byte[] maskingKey = null;
        if (masked) {
            maskingKey = new byte[4];
            readFully(maskingKey);
        }

        byte[] payload = payloadLength == 0 ? WebSocketFrame.EMPTY_PAYLOAD : new byte[(int) payloadLength];
        readFully(payload);
        if (maskingKey != null) {
            unmask(payload, maskingKey);
        }

        return new WebSocketFrame(fin, opCode, payload);
    }

    public static void unmask(byte[] payload, byte[] maskingKey) {
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (payload[i] ^ maskingKey[i & 3]);
        }
    }

    private int readByte() throws IOException {
        int value;
        try {
            value = input.read();
        } catch (SocketTimeoutException e) {
            throw new IOException("Read timed out in the middle of a WebSocket frame", e);
        }
        if (value == -1) {
            throw new EOFException("Connection closed in the middle of a WebSocket frame");
        }
        return value;
    }

    private void readFully(byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int count;
            try {
                count = input.read(buffer, offset, buffer.length - offset);
            } catch (SocketTimeoutException e) {
                throw new IOException("Read timed out in the middle of a WebSocket frame", e);
            }
            if (count == -1) {
                throw new EOFException("Connection closed in the middle of a WebSocket frame");
            }
            offset += count;
        }
    }
}
