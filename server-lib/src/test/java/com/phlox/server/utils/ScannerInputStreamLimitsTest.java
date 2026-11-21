package com.phlox.server.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

public class ScannerInputStreamLimitsTest {

    private static ScannerInputStream of(String data) {
        return new ScannerInputStream(new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void lineAtTheLimitIsAccepted() throws Exception {
        ScannerInputStream input = of("12345\r\nnext\r\n");
        assertEquals("12345", input.nextLine("ASCII", 5));
        assertEquals("next", input.nextLine("ASCII", 5));
    }

    @Test
    public void longerLineIsRejected() {
        ScannerInputStream input = of("123456\r\n");
        assertThrows(ScannerInputStream.LimitExceededException.class, () -> input.nextLine("ASCII", 5));
    }

    @Test
    public void lineWithoutTerminatorIsBoundedToo() {
        //a client that never sends CRLF must not make the server buffer forever
        ScannerInputStream input = of(repeat('x', 100_000));
        assertThrows(ScannerInputStream.LimitExceededException.class, () -> input.nextLine("ASCII", 8192));
    }

    @Test
    public void partialDelimiterBytesCountTowardsTheLimit() {
        ScannerInputStream input = of("\r\r\r\r\r\r\r\r\r\r\n");
        assertThrows(ScannerInputStream.LimitExceededException.class, () -> input.nextLine("ASCII", 3));
    }

    @Test
    public void deadlineStopsATricklingClient() {
        ScannerInputStream input = new ScannerInputStream(new SlowStream("GET / HTTP/1.1\r\nHost: x\r\n\r\n", 0, 40));
        input.startDeadlineOnNextByte(100);
        assertThrows(SocketTimeoutException.class, () -> {
            while (input.nextLine("ASCII") != null) {
                //keep reading
            }
        });
    }

    @Test
    public void deadlineStartsWithTheFirstByte() throws Exception {
        //a keep-alive connection may sit idle for long before the next request starts
        ScannerInputStream input = new ScannerInputStream(new SlowStream("GET / HTTP/1.1\r\n", 300, 0));
        input.startDeadlineOnNextByte(150);
        assertEquals("GET / HTTP/1.1", input.nextLine("ASCII"));
    }

    @Test
    public void clearedDeadlineDoesNotFire() throws Exception {
        //the line arrives at once, the body only after the deadline has passed. Laid out this way
        //rather than as an evenly trickling stream because Thread.sleep only ever overshoots, and
        //on a loaded CI machine (macOS runners especially) by tens of milliseconds per call: the
        //line must not depend on any sleep to stay inside the deadline, and the body read is past
        //it however late the sleep returns
        ScannerInputStream input = new ScannerInputStream(new SequenceInputStream(
                new ByteArrayInputStream("a\r\n".getBytes(StandardCharsets.US_ASCII)),
                new SlowStream("body", 400, 0)));
        input.startDeadlineOnNextByte(200);
        assertEquals("a", input.nextLine("ASCII"));
        input.clearDeadline();
        assertEquals('b', input.read());
        assertEquals('o', input.read());
        assertEquals('d', input.read());
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    /** Hands out one byte at a time, with a delay before the first and between the others. */
    private static class SlowStream extends InputStream {
        private final byte[] data;
        private final long firstDelayMillis;
        private final long delayMillis;
        private int position = 0;

        SlowStream(String data, long firstDelayMillis, long delayMillis) {
            this.data = data.getBytes(StandardCharsets.US_ASCII);
            this.firstDelayMillis = firstDelayMillis;
            this.delayMillis = delayMillis;
        }

        @Override
        public int read() {
            if (position >= data.length) {
                return -1;
            }
            try {
                Thread.sleep(position == 0 ? firstDelayMillis : delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return data[position++] & 0xFF;
        }
    }
}
