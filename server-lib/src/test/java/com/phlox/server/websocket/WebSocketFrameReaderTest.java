package com.phlox.server.websocket;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class WebSocketFrameReaderTest {
    private static final byte[] MASK = {0x37, (byte) 0xfa, 0x21, 0x3d};

    @Test
    public void readsMaskedTextFrame() throws IOException {
        //example from RFC 6455 section 5.7
        byte[] wire = {(byte) 0x81, (byte) 0x85, 0x37, (byte) 0xfa, 0x21, 0x3d,
                0x7f, (byte) 0x9f, 0x4d, 0x51, 0x58};
        WebSocketFrame frame = reader(wire).readFrame();

        assertTrue(frame.fin);
        assertEquals(WebSocketFrame.OPCODE_TEXT, frame.opCode);
        assertEquals("Hello", new String(frame.payload, StandardCharsets.UTF_8));
    }

    @Test
    public void readsEmptyAndFragmentedFrames() throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        wire.write(clientFrame(false, WebSocketFrame.OPCODE_TEXT, "abc".getBytes(StandardCharsets.UTF_8)));
        wire.write(clientFrame(true, WebSocketFrame.OPCODE_CONTINUATION, new byte[0]));

        WebSocketFrameReader reader = reader(wire.toByteArray());

        WebSocketFrame first = reader.readFrame();
        assertFalse(first.fin);
        assertEquals(WebSocketFrame.OPCODE_TEXT, first.opCode);
        assertEquals("abc", new String(first.payload, StandardCharsets.UTF_8));

        WebSocketFrame second = reader.readFrame();
        assertTrue(second.fin);
        assertEquals(WebSocketFrame.OPCODE_CONTINUATION, second.opCode);
        assertEquals(0, second.payload.length);

        //nothing left on the wire
        assertNull(reader.readFrame());
    }

    @Test
    public void readsExtendedPayloadLengths() throws IOException {
        byte[] medium = payloadOfLength(1000);
        WebSocketFrame mediumFrame = reader(clientFrame(true, WebSocketFrame.OPCODE_BINARY, medium)).readFrame();
        assertArrayEquals(medium, mediumFrame.payload);

        byte[] large = payloadOfLength(70000);
        WebSocketFrameReader reader = new WebSocketFrameReader(
                new ByteArrayInputStream(clientFrame(true, WebSocketFrame.OPCODE_BINARY, large)),
                1024 * 1024, true);
        assertArrayEquals(large, reader.readFrame().payload);
    }

    @Test
    public void rejectsUnmaskedClientFrame() {
        byte[] wire = {(byte) 0x81, 0x02, 'h', 'i'};
        WebSocketProtocolException e = assertThrows(WebSocketProtocolException.class,
                () -> reader(wire).readFrame());
        assertEquals(WebSocketCloseCodes.PROTOCOL_ERROR, e.closeCode);
    }

    @Test
    public void rejectsReservedBits() {
        byte[] frame = clientFrame(true, WebSocketFrame.OPCODE_TEXT, "hi".getBytes(StandardCharsets.UTF_8));
        frame[0] |= 0x40;//RSV1
        WebSocketProtocolException e = assertThrows(WebSocketProtocolException.class,
                () -> reader(frame).readFrame());
        assertEquals(WebSocketCloseCodes.PROTOCOL_ERROR, e.closeCode);
    }

    @Test
    public void rejectsUnknownOpCode() {
        byte[] frame = clientFrame(true, 0x3, "hi".getBytes(StandardCharsets.UTF_8));
        WebSocketProtocolException e = assertThrows(WebSocketProtocolException.class,
                () -> reader(frame).readFrame());
        assertEquals(WebSocketCloseCodes.PROTOCOL_ERROR, e.closeCode);
    }

    @Test
    public void rejectsFragmentedControlFrame() {
        byte[] frame = clientFrame(false, WebSocketFrame.OPCODE_PING, new byte[0]);
        WebSocketProtocolException e = assertThrows(WebSocketProtocolException.class,
                () -> reader(frame).readFrame());
        assertEquals(WebSocketCloseCodes.PROTOCOL_ERROR, e.closeCode);
    }

    @Test
    public void rejectsOversizedControlFrame() {
        byte[] frame = clientFrame(true, WebSocketFrame.OPCODE_PING, payloadOfLength(126));
        WebSocketProtocolException e = assertThrows(WebSocketProtocolException.class,
                () -> reader(frame).readFrame());
        assertEquals(WebSocketCloseCodes.PROTOCOL_ERROR, e.closeCode);
    }

    @Test
    public void rejectsNonMinimalLengthEncoding() {
        //a 5 byte payload announced with the 16 bit length form
        byte[] wire = {(byte) 0x81, (byte) (0x80 | 126), 0x00, 0x05,
                MASK[0], MASK[1], MASK[2], MASK[3], 0, 0, 0, 0, 0};
        WebSocketProtocolException e = assertThrows(WebSocketProtocolException.class,
                () -> reader(wire).readFrame());
        assertEquals(WebSocketCloseCodes.PROTOCOL_ERROR, e.closeCode);
    }

    @Test
    public void rejectsFrameOverTheSizeLimit() {
        byte[] frame = clientFrame(true, WebSocketFrame.OPCODE_BINARY, payloadOfLength(500));
        WebSocketFrameReader reader = new WebSocketFrameReader(new ByteArrayInputStream(frame), 100, true);
        WebSocketProtocolException e = assertThrows(WebSocketProtocolException.class, reader::readFrame);
        assertEquals(WebSocketCloseCodes.MESSAGE_TOO_BIG, e.closeCode);
    }

    @Test
    public void reportsEndOfStreamOnlyBetweenFrames() throws IOException {
        //no data at all means the peer went away between frames
        assertNull(reader(new byte[0]).readFrame());

        //a frame that stops in the middle is a broken connection
        byte[] truncated = {(byte) 0x81, (byte) 0x85, 0x37, (byte) 0xfa};
        assertThrows(EOFException.class, () -> reader(truncated).readFrame());
    }

    private static WebSocketFrameReader reader(byte[] wire) {
        return new WebSocketFrameReader(new ByteArrayInputStream(wire), 1024 * 1024, true);
    }

    private static byte[] payloadOfLength(int length) {
        byte[] payload = new byte[length];
        for (int i = 0; i < length; i++) {
            payload[i] = (byte) (i % 251);
        }
        return payload;
    }

    /**
     * Builds a masked frame the way a client would send it.
     */
    static byte[] clientFrame(boolean fin, int opCode, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((fin ? 0x80 : 0x00) | (opCode & 0x0F));
        int length = payload.length;
        if (length < 126) {
            out.write(0x80 | length);
        } else if (length <= 0xFFFF) {
            out.write(0x80 | 126);
            out.write(length >>> 8);
            out.write(length);
        } else {
            out.write(0x80 | 127);
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) ((long) length >>> shift));
            }
        }
        out.write(MASK, 0, MASK.length);
        for (int i = 0; i < length; i++) {
            out.write(payload[i] ^ MASK[i & 3]);
        }
        return out.toByteArray();
    }
}
