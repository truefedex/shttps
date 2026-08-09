package com.phlox.server.websocket;

/**
 * A single RFC 6455 frame with its payload already unmasked.
 */
public class WebSocketFrame {
    public static final int OPCODE_CONTINUATION = 0x0;
    public static final int OPCODE_TEXT = 0x1;
    public static final int OPCODE_BINARY = 0x2;
    public static final int OPCODE_CLOSE = 0x8;
    public static final int OPCODE_PING = 0x9;
    public static final int OPCODE_PONG = 0xA;

    public static final byte[] EMPTY_PAYLOAD = new byte[0];

    public final boolean fin;
    public final int opCode;
    public final byte[] payload;

    public WebSocketFrame(boolean fin, int opCode, byte[] payload) {
        this.fin = fin;
        this.opCode = opCode;
        this.payload = payload != null ? payload : EMPTY_PAYLOAD;
    }

    public boolean isControlFrame() {
        return isControlOpCode(opCode);
    }

    /**
     * Control frames (close, ping, pong) are the ones with the high bit of the opcode set.
     * They may appear in the middle of a fragmented message and are never fragmented themselves.
     */
    public static boolean isControlOpCode(int opCode) {
        return (opCode & 0x08) != 0;
    }

    public static boolean isKnownOpCode(int opCode) {
        return opCode == OPCODE_CONTINUATION || opCode == OPCODE_TEXT || opCode == OPCODE_BINARY ||
                opCode == OPCODE_CLOSE || opCode == OPCODE_PING || opCode == OPCODE_PONG;
    }

    @Override
    public String toString() {
        return "WebSocketFrame{fin=" + fin + ", opCode=0x" + Integer.toHexString(opCode) +
                ", payloadLength=" + payload.length + "}";
    }
}
