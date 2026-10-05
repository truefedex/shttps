package com.phlox.server.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.phlox.server.request.Request;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class WebSocketHandlerHelpersTest {

    private static Request withHost(String host) {
        Request request = new Request();
        if (host != null) {
            request.headers.add(Request.HEADER_HOST, host);
        }
        return request;
    }

    @Test
    public void sameOriginCheck() {
        //what a cookie-authenticated endpoint uses against cross-site WebSocket hijacking
        assertTrue(WebSocketRequestHandler.isSameOriginAsHost(withHost("myhost:8080"), "http://myhost:8080"));
        assertTrue(WebSocketRequestHandler.isSameOriginAsHost(withHost("MyHost:8080"), "https://myhost:8080"));
        assertTrue(WebSocketRequestHandler.isSameOriginAsHost(withHost("myhost"), null), "non-browser clients send no Origin");
        assertFalse(WebSocketRequestHandler.isSameOriginAsHost(withHost("myhost:8080"), "http://evil.example"));
        assertFalse(WebSocketRequestHandler.isSameOriginAsHost(withHost("myhost:8080"), "http://myhost:8081"));
        assertFalse(WebSocketRequestHandler.isSameOriginAsHost(withHost("myhost"), "http://myhost.evil.example"));
        assertFalse(WebSocketRequestHandler.isSameOriginAsHost(withHost(null), "http://myhost"));
        assertFalse(WebSocketRequestHandler.isSameOriginAsHost(withHost("myhost"), "null"));
    }

    private static WebSocketSession session(OutputStream out) {
        return new WebSocketSession(new Socket(), new ByteArrayInputStream(new byte[0]), out,
                new Request(), null, new WebSocketOptions());
    }

    private static WebSocketFrame onlyFrame(ByteArrayOutputStream out) throws IOException {
        WebSocketFrameReader reader = new WebSocketFrameReader(new ByteArrayInputStream(out.toByteArray()), 1 << 20, false);
        WebSocketFrame frame = reader.readFrame();
        assertNull(reader.readFrame());
        return frame;
    }

    @Test
    public void broadcastReachesOpenSessionsAndDropsBrokenOnes() throws IOException {
        ByteArrayOutputStream first = new ByteArrayOutputStream();
        ByteArrayOutputStream closedOut = new ByteArrayOutputStream();
        WebSocketSession open = session(first);
        WebSocketSession closed = session(closedOut);
        closed.close();
        closedOut.reset();
        WebSocketSession broken = session(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("peer gone");
            }
        });

        WebSocketRequestHandler.broadcastText(Arrays.asList(open, closed, broken), "news");

        assertEquals("news", new String(onlyFrame(first).payload, StandardCharsets.UTF_8));
        assertEquals(0, closedOut.size(), "a closed session is skipped");
        assertFalse(broken.isOpen(), "a session that fails to send is closed");
        assertEquals(WebSocketCloseCodes.ABNORMAL_CLOSURE, broken.getCloseCode());
    }

    @Test
    public void binaryBroadcast() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WebSocketRequestHandler.broadcastBinary(Arrays.asList(session(out)), new byte[]{1, 2, 3});
        WebSocketFrame frame = onlyFrame(out);
        assertEquals(WebSocketFrame.OPCODE_BINARY, frame.opCode);
        assertEquals(3, frame.payload.length);
    }

    @Test
    public void longMessagesAreFragmented() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WebSocketSession session = session(out);
        session.options.outgoingFrameLength = 4;
        session.sendBinary(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, 0, 10);
        WebSocketFrameReader reader = new WebSocketFrameReader(new ByteArrayInputStream(out.toByteArray()), 1 << 20, false);
        WebSocketFrame first = reader.readFrame();
        assertEquals(WebSocketFrame.OPCODE_BINARY, first.opCode);
        assertFalse(first.fin);
        assertEquals(WebSocketFrame.OPCODE_CONTINUATION, reader.readFrame().opCode);
        WebSocketFrame last = reader.readFrame();
        assertTrue(last.fin);
        assertEquals(2, last.payload.length);
    }
}
