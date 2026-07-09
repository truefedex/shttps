package com.phlox.server.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class ChunkedBodyStreamTest {

    private static InputStream wire(String data) {
        return new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[7]; //odd size on purpose, to cross chunk borders
        int count;
        while ((count = in.read(buffer, 0, buffer.length)) != -1) {
            out.write(buffer, 0, count);
        }
        return out.toString("UTF-8");
    }

    @Test
    public void decodesMultipleChunks() throws IOException {
        ChunkedBodyStream body = new ChunkedBodyStream(wire(
                "5\r\nHello\r\n7\r\n, chunk\r\nB\r\ned world!!!\r\n0\r\n\r\n"));
        assertFalse(body.isFullyConsumed());
        assertEquals("Hello, chunked world!!!", readAll(body));
        assertTrue(body.isFullyConsumed());
    }

    @Test
    public void stopsExactlyAtTerminalChunk() throws IOException {
        InputStream base = wire("3\r\nabc\r\n0\r\n\r\nNEXT-REQUEST");
        ChunkedBodyStream body = new ChunkedBodyStream(base);
        assertEquals("abc", readAll(body));
        assertTrue(body.isFullyConsumed());
        //bytes of the next pipelined request must stay untouched
        assertEquals('N', base.read());
    }

    @Test
    public void ignoresChunkExtensionsAndTrailers() throws IOException {
        ChunkedBodyStream body = new ChunkedBodyStream(wire(
                "4;name=value\r\ndata\r\n0\r\nX-Trailer: something\r\nAnother: one\r\n\r\n"));
        assertEquals("data", readAll(body));
        assertTrue(body.isFullyConsumed());
    }

    @Test
    public void emptyBodyIsJustTerminalChunk() throws IOException {
        InputStream base = wire("0\r\n\r\nG");
        ChunkedBodyStream body = new ChunkedBodyStream(base);
        assertEquals(-1, body.read());
        assertTrue(body.isFullyConsumed());
        assertEquals('G', base.read());
    }

    @Test
    public void singleByteReadsWork() throws IOException {
        ChunkedBodyStream body = new ChunkedBodyStream(wire("2\r\nhi\r\n0\r\n\r\n"));
        assertEquals('h', body.read());
        assertEquals('i', body.read());
        assertEquals(-1, body.read());
        assertTrue(body.isFullyConsumed());
    }

    @Test
    public void drainRemainingConsumesRestUpToLimit() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append("400\r\n"); //1024 bytes per chunk
            for (int j = 0; j < 64; j++) {
                sb.append("0123456789abcdef");
            }
            sb.append("\r\n");
        }
        sb.append("0\r\n\r\n");

        ChunkedBodyStream body = new ChunkedBodyStream(wire(sb.toString()));
        //limit below the total size: not fully drained
        assertFalse(body.drainRemaining(2048));
        assertFalse(body.isFullyConsumed());
        //now drain the rest
        assertTrue(body.drainRemaining(Long.MAX_VALUE));
        assertTrue(body.isFullyConsumed());
    }

    @Test
    public void malformedChunkSizeThrows() {
        ChunkedBodyStream body = new ChunkedBodyStream(wire("zzz\r\ndata\r\n0\r\n\r\n"));
        assertThrows(IOException.class, () -> readAll(body));
    }

    @Test
    public void missingCrlfAfterChunkDataThrows() {
        ChunkedBodyStream body = new ChunkedBodyStream(wire("3\r\nabcX\r\n0\r\n\r\n"));
        assertThrows(IOException.class, () -> readAll(body));
    }

    @Test
    public void prematureEofInChunkDataCountsAsConsumed() throws IOException {
        //client promised 10 bytes in the chunk but the connection ended after 3
        ChunkedBodyStream body = new ChunkedBodyStream(wire("A\r\nabc"));
        byte[] buffer = new byte[100];
        assertEquals(3, body.read(buffer, 0, 100));
        assertEquals(-1, body.read(buffer, 0, 100));
        assertTrue(body.isFullyConsumed());
    }

    @Test
    public void prematureEofInChunkHeaderThrows() {
        ChunkedBodyStream body = new ChunkedBodyStream(wire("3\r\nabc\r\n"));
        assertThrows(IOException.class, () -> readAll(body));
    }
}
