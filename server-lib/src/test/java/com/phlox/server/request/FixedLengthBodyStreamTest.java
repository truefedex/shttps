package com.phlox.server.request;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class FixedLengthBodyStreamTest {

    @Test
    public void reportsEofAfterExactlyLengthBytes() throws IOException {
        byte[] wire = "0123456789NEXT-REQUEST".getBytes(StandardCharsets.UTF_8);
        InputStream base = new ByteArrayInputStream(wire);
        FixedLengthBodyStream body = new FixedLengthBodyStream(base, 10);

        ByteArrayOutputStream read = new ByteArrayOutputStream();
        int b;
        while ((b = body.read()) != -1) {
            read.write(b);
        }

        assertEquals("0123456789", read.toString("UTF-8"));
        assertTrue(body.isFullyConsumed());
        //bytes after the body must stay untouched in the underlying stream
        assertEquals('N', base.read());
    }

    @Test
    public void bulkReadDoesNotOverreadPastBody() throws IOException {
        byte[] wire = "abcdefXYZ".getBytes(StandardCharsets.UTF_8);
        InputStream base = new ByteArrayInputStream(wire);
        FixedLengthBodyStream body = new FixedLengthBodyStream(base, 6);

        byte[] buffer = new byte[100];
        int count = body.read(buffer, 0, buffer.length);
        assertEquals(6, count);
        assertArrayEquals("abcdef".getBytes(StandardCharsets.UTF_8),
                java.util.Arrays.copyOf(buffer, count));
        assertEquals(-1, body.read(buffer, 0, buffer.length));
        assertEquals('X', base.read());
    }

    @Test
    public void zeroAndNegativeLengthMeanNoBody() throws IOException {
        FixedLengthBodyStream body = new FixedLengthBodyStream(
                new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)), 0);
        assertTrue(body.isFullyConsumed());
        assertEquals(-1, body.read());

        //Content-Length absent is stored as -1 and must behave as "no body"
        FixedLengthBodyStream noLength = new FixedLengthBodyStream(
                new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)), -1);
        assertTrue(noLength.isFullyConsumed());
        assertEquals(-1, noLength.read());
    }

    @Test
    public void drainRemainingConsumesRestUpToLimit() throws IOException {
        byte[] wire = new byte[1000];
        FixedLengthBodyStream body = new FixedLengthBodyStream(new ByteArrayInputStream(wire), 900);

        //read a part, then drain the rest
        byte[] buffer = new byte[100];
        assertEquals(100, body.read(buffer, 0, 100));
        assertFalse(body.isFullyConsumed());

        //drain with a limit smaller than what is left
        assertFalse(body.drainRemaining(300));
        assertEquals(500, body.remaining());

        //drain with a sufficient limit
        assertTrue(body.drainRemaining(10000));
        assertTrue(body.isFullyConsumed());
    }

    @Test
    public void prematureClientEofCountsAsConsumed() throws IOException {
        //client promised 100 bytes but the connection ended after 5
        FixedLengthBodyStream body = new FixedLengthBodyStream(
                new ByteArrayInputStream("12345".getBytes(StandardCharsets.UTF_8)), 100);
        byte[] buffer = new byte[100];
        assertEquals(5, body.read(buffer, 0, 100));
        assertEquals(-1, body.read(buffer, 0, 100));
        assertTrue(body.isFullyConsumed());
    }
}
