package com.phlox.server.websocket;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes RFC 6455 frames to a connection stream. Frames written by a server are never masked.
 * <p>
 * All write methods are synchronized on this object, so messages sent from different threads
 * can not interleave with each other in the middle of a frame.
 */
public class WebSocketFrameWriter {
    private final OutputStream output;

    public WebSocketFrameWriter(OutputStream output) {
        this.output = output;
    }

    public synchronized void writeFrame(boolean fin, int opCode, byte[] payload, int offset, int length) throws IOException {
        byte[] header = new byte[10];
        header[0] = (byte) ((fin ? 0x80 : 0x00) | (opCode & 0x0F));
        int headerLength;
        if (length < 126) {
            header[1] = (byte) length;
            headerLength = 2;
        } else if (length <= 0xFFFF) {
            header[1] = 126;
            header[2] = (byte) (length >>> 8);
            header[3] = (byte) length;
            headerLength = 4;
        } else {
            header[1] = 127;
            //bytes 2-5 stay zero: a Java array can never be longer than 2^31-1
            header[6] = (byte) (length >>> 24);
            header[7] = (byte) (length >>> 16);
            header[8] = (byte) (length >>> 8);
            header[9] = (byte) length;
            headerLength = 10;
        }

        //header and payload leave in one write. The stream underneath is buffered, and a buffered
        //stream handed more than it holds first flushes what it has - here the few header bytes,
        //as a TCP segment of their own - and then passes the payload straight through. On a socket
        //with Nagle's algorithm on, the tail of the payload then waits for that tiny segment to be
        //acknowledged, which the peer may put off for tens of milliseconds
        if (length > 0) {
            byte[] frame = new byte[headerLength + length];
            System.arraycopy(header, 0, frame, 0, headerLength);
            System.arraycopy(payload, offset, frame, headerLength, length);
            output.write(frame, 0, frame.length);
        } else {
            output.write(header, 0, headerLength);
        }
        output.flush();
    }

    public synchronized void writeFrame(boolean fin, int opCode, byte[] payload) throws IOException {
        writeFrame(fin, opCode, payload, 0, payload.length);
    }

    /**
     * Writes a complete message, splitting it into fragments of at most {@code maxFrameLength}
     * bytes (a non-positive value means "always send a single frame").
     */
    public synchronized void writeMessage(int opCode, byte[] payload, int offset, int length,
                                          int maxFrameLength) throws IOException {
        if (maxFrameLength <= 0 || length <= maxFrameLength) {
            writeFrame(true, opCode, payload, offset, length);
            return;
        }
        int written = 0;
        while (written < length) {
            int chunkLength = Math.min(maxFrameLength, length - written);
            boolean last = written + chunkLength == length;
            writeFrame(last, written == 0 ? opCode : WebSocketFrame.OPCODE_CONTINUATION,
                    payload, offset + written, chunkLength);
            written += chunkLength;
        }
    }
}
