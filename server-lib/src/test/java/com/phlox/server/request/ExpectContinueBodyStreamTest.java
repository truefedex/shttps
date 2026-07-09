package com.phlox.server.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public class ExpectContinueBodyStreamTest {

    private static ExpectContinueBodyStream stream(String body, AtomicInteger continueCount) {
        FixedLengthBodyStream inner = new FixedLengthBodyStream(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), body.length());
        return new ExpectContinueBodyStream(inner, continueCount::incrementAndGet);
    }

    @Test
    public void sendsContinueExactlyOnceBeforeFirstRead() throws IOException {
        AtomicInteger continueCount = new AtomicInteger();
        ExpectContinueBodyStream body = stream("data", continueCount);

        assertEquals(0, continueCount.get());
        assertFalse(body.isContinueSent());

        assertEquals('d', body.read());
        assertEquals(1, continueCount.get());
        assertTrue(body.isContinueSent());

        byte[] buffer = new byte[10];
        assertEquals(3, body.read(buffer, 0, buffer.length));
        assertEquals(1, continueCount.get());
        assertTrue(body.isFullyConsumed());
    }

    @Test
    public void neverSendsContinueIfBodyIsNeverRead() throws IOException {
        AtomicInteger continueCount = new AtomicInteger();
        ExpectContinueBodyStream body = stream("data", continueCount);

        assertFalse(body.isFullyConsumed());
        assertEquals(0, body.available());
        assertEquals(0, continueCount.get());
    }

    @Test
    public void drainWithoutContinueDoesNotProvokeUpload() throws IOException {
        AtomicInteger continueCount = new AtomicInteger();
        ExpectContinueBodyStream body = stream("data", continueCount);

        //the client never got 100 Continue, so there is nothing to drain - the server
        //must treat the body as pending and close the connection
        assertFalse(body.drainRemaining(1000));
        assertEquals(0, continueCount.get());
        assertFalse(body.isFullyConsumed());
    }

    @Test
    public void drainAfterContinueDrainsNormally() throws IOException {
        AtomicInteger continueCount = new AtomicInteger();
        ExpectContinueBodyStream body = stream("data", continueCount);

        assertEquals('d', body.read());
        assertTrue(body.drainRemaining(1000));
        assertTrue(body.isFullyConsumed());
        assertEquals(1, continueCount.get());
    }

    @Test
    public void detachingSendsContinueEagerly() throws IOException {
        AtomicInteger continueCount = new AtomicInteger();
        ExpectContinueBodyStream body = stream("data", continueCount);

        //hand-off to another thread (CGI pump) must send 100 Continue synchronously,
        //while the current thread is still the only writer on the connection
        body.markDetached();
        assertEquals(1, continueCount.get());
        assertTrue(body.isDetached());
    }
}
