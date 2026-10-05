package com.phlox.server.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.phlox.server.request.Request;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

@Timeout(60)
public class WebSocketSessionTest {

    private static WebSocketSession session(ByteArrayOutputStream out) {
        //the socket is only asked for timeouts and whether it is closed
        return new WebSocketSession(new Socket(), new ByteArrayInputStream(new byte[0]), out,
                new Request(), null, new WebSocketOptions());
    }

    @Test
    public void nothingIsSentAfterClose() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WebSocketSession session = session(out);
        session.sendText("before");
        session.close(WebSocketCloseCodes.GOING_AWAY, "bye");
        assertFalse(session.isOpen());
        assertThrows(IOException.class, () -> session.sendText("after"));
        assertThrows(IOException.class, () -> session.sendBinary(new byte[]{1}));
        assertThrows(IOException.class, () -> session.sendPing(new byte[0]));

        WebSocketFrameReader reader = new WebSocketFrameReader(new ByteArrayInputStream(out.toByteArray()), 1 << 20, false);
        assertEquals("before", new String(reader.readFrame().payload, StandardCharsets.UTF_8));
        WebSocketFrame close = reader.readFrame();
        assertEquals(WebSocketFrame.OPCODE_CLOSE, close.opCode);
        assertEquals(WebSocketCloseCodes.GOING_AWAY, ((close.payload[0] & 0xFF) << 8) | (close.payload[1] & 0xFF));
        assertEquals(null, reader.readFrame());
    }

    @Test
    public void closeRacingWithASenderNeverLeavesAFrameAfterTheCloseFrame() throws Exception {
        //the open check used to happen outside the writer's lock: a message checked before close()
        //could be written after the close frame. To get there for certain, the test holds the
        //writer's lock while a sender and close() both line up behind it, then lets them race
        Field writerField = WebSocketSession.class.getDeclaredField("writer");
        writerField.setAccessible(true);
        for (int round = 0; round < 30; round++) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            WebSocketSession session = session(out);
            Object writer = writerField.get(session);
            Thread sender = new Thread(() -> {
                try {
                    session.sendText("x");
                } catch (IOException expected) {
                    //lost the race to close(): that is the correct outcome
                }
            });
            Thread closer = new Thread(session::close);
            synchronized (writer) {
                sender.start();
                awaitBlocked(sender);
                closer.start();
                awaitBlocked(closer);
            }
            sender.join(10_000);
            closer.join(10_000);

            WebSocketFrameReader reader = new WebSocketFrameReader(new ByteArrayInputStream(out.toByteArray()), 1 << 20, false);
            boolean closeSeen = false;
            WebSocketFrame frame;
            while ((frame = reader.readFrame()) != null) {
                assertFalse(closeSeen, "frame after the close frame in round " + round);
                closeSeen = frame.opCode == WebSocketFrame.OPCODE_CLOSE;
            }
            assertTrue(closeSeen);
        }
    }

    private static void awaitBlocked(Thread thread) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (thread.getState() != Thread.State.BLOCKED) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError(thread + " never blocked on the writer");
            }
            Thread.sleep(1);
        }
    }

    @Test
    public void closeReasonIsTruncatedOnACharacterBoundary() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WebSocketSession session = session(out);
        StringBuilder reason = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            reason.append('ж'); //two bytes each
        }
        session.close(WebSocketCloseCodes.NORMAL_CLOSURE, reason.toString());
        WebSocketFrame close = new WebSocketFrameReader(new ByteArrayInputStream(out.toByteArray()), 1 << 20, false).readFrame();
        assertTrue(close.payload.length <= 125);
        //decodes as whole characters
        String sent = new String(close.payload, 2, close.payload.length - 2, StandardCharsets.UTF_8);
        assertFalse(sent.contains("�"));
        assertEquals(61, sent.length());
    }

    @Test
    public void controlPayloadOver125BytesIsRefused() {
        WebSocketSession session = session(new ByteArrayOutputStream());
        assertThrows(IllegalArgumentException.class, () -> session.sendPing(new byte[126]));
    }

    @Test
    public void localOnlyCloseCodesAreNotPutOnTheWire() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        session(out).close(WebSocketCloseCodes.ABNORMAL_CLOSURE, "never sent");
        WebSocketFrame close = new WebSocketFrameReader(new ByteArrayInputStream(out.toByteArray()), 1 << 20, false).readFrame();
        assertEquals(0, close.payload.length);
    }
}
