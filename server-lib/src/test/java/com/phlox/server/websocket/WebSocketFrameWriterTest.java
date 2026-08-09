package com.phlox.server.websocket;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class WebSocketFrameWriterTest {

    @Test
    public void writesShortUnmaskedFrame() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new WebSocketFrameWriter(out).writeFrame(true, WebSocketFrame.OPCODE_TEXT,
                "Hello".getBytes(StandardCharsets.UTF_8));

        //example from RFC 6455 section 5.7: a server frame is never masked
        assertArrayEquals(new byte[]{(byte) 0x81, 0x05, 'H', 'e', 'l', 'l', 'o'}, out.toByteArray());
    }

    @Test
    public void picksTheShortestLengthEncoding() throws IOException {
        assertEquals(2, headerLengthFor(125));
        assertEquals(4, headerLengthFor(126));
        assertEquals(4, headerLengthFor(0xFFFF));
        assertEquals(10, headerLengthFor(0x10000));
    }

    @Test
    public void writtenFramesAreReadableBack() throws IOException {
        byte[] payload = new byte[70000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new WebSocketFrameWriter(out).writeFrame(true, WebSocketFrame.OPCODE_BINARY, payload);

        WebSocketFrame frame = serverFrameReader(out.toByteArray()).readFrame();
        assertEquals(WebSocketFrame.OPCODE_BINARY, frame.opCode);
        assertArrayEquals(payload, frame.payload);
    }

    @Test
    public void splitsLongMessagesIntoFragments() throws IOException {
        byte[] payload = "0123456789".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new WebSocketFrameWriter(out).writeMessage(WebSocketFrame.OPCODE_TEXT, payload, 0, payload.length, 4);

        WebSocketFrameReader reader = serverFrameReader(out.toByteArray());

        WebSocketFrame first = reader.readFrame();
        assertFalse(first.fin);
        assertEquals(WebSocketFrame.OPCODE_TEXT, first.opCode);
        assertEquals("0123", new String(first.payload, StandardCharsets.UTF_8));

        WebSocketFrame second = reader.readFrame();
        assertFalse(second.fin);
        assertEquals(WebSocketFrame.OPCODE_CONTINUATION, second.opCode);
        assertEquals("4567", new String(second.payload, StandardCharsets.UTF_8));

        WebSocketFrame third = reader.readFrame();
        assertTrue(third.fin);
        assertEquals(WebSocketFrame.OPCODE_CONTINUATION, third.opCode);
        assertEquals("89", new String(third.payload, StandardCharsets.UTF_8));
    }

    @Test
    public void sendsSingleFrameWhenFragmentationIsDisabled() throws IOException {
        byte[] payload = "0123456789".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new WebSocketFrameWriter(out).writeMessage(WebSocketFrame.OPCODE_TEXT, payload, 0, payload.length, 0);

        WebSocketFrame frame = serverFrameReader(out.toByteArray()).readFrame();
        assertTrue(frame.fin);
        assertEquals("0123456789", new String(frame.payload, StandardCharsets.UTF_8));
    }

    private static int headerLengthFor(int payloadLength) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new WebSocketFrameWriter(out).writeFrame(true, WebSocketFrame.OPCODE_BINARY, new byte[payloadLength]);
        return out.size() - payloadLength;
    }

    private static WebSocketFrameReader serverFrameReader(byte[] wire) {
        return new WebSocketFrameReader(new ByteArrayInputStream(wire), 1024 * 1024, false);
    }
}
