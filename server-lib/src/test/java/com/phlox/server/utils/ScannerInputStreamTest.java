package com.phlox.server.utils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class ScannerInputStreamTest {

    private static ScannerInputStream of(String data) {
        return new ScannerInputStream(new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void bulkReadDelegatesToBaseWhenNothingPushedBack() throws Exception {
        ScannerInputStream input = of("raw body content");

        byte[] buffer = new byte[8192];
        int count = input.read(buffer, 0, buffer.length);

        assertEquals(16, count);
        assertEquals("raw body content", new String(buffer, 0, count, StandardCharsets.UTF_8));
        assertEquals(-1, input.read(buffer, 0, buffer.length));
    }

    @Test
    public void bulkReadNeverReadsPastRequestedLength() throws Exception {
        ScannerInputStream input = of("body-of-a-request" + "GET / HTTP/1.1\r\n");

        byte[] buffer = new byte[17];
        int count = input.read(buffer, 0, buffer.length);

        assertEquals(17, count);
        assertEquals("body-of-a-request", new String(buffer, 0, count, StandardCharsets.UTF_8));
        //the pipelined next request must still be there untouched
        assertEquals("GET / HTTP/1.1", input.nextLine());
    }

    @Test
    public void bulkReadHonoursOffsetAndLength() throws Exception {
        ScannerInputStream input = of("abcdef");

        byte[] buffer = new byte[6];
        Arrays.fill(buffer, (byte) '.');
        int count = input.read(buffer, 2, 3);

        assertEquals(3, count);
        assertArrayEquals("..abc.".getBytes(StandardCharsets.UTF_8), buffer);
    }

    @Test
    public void bulkReadOfZeroLengthReadsNothing() throws Exception {
        ScannerInputStream input = of("abc");

        assertEquals(0, input.read(new byte[4], 0, 0));
        assertEquals('a', input.read());
    }

    /**
     * The one case where a naive {@code return base.read(b, off, len)} would silently
     * drop data: bytes handed back by the delimiter scanner must be served first.
     */
    @Test
    public void bulkReadServesPushedBackBytesBeforeBaseStream() throws Exception {
        ScannerInputStream input = of("DEF");
        input.writeBack("ABC".getBytes(StandardCharsets.UTF_8), 0, 3);

        byte[] buffer = new byte[8192];
        //pushed-back bytes are never mixed with fresh data, so this is a short read
        int first = input.read(buffer, 0, buffer.length);
        assertEquals(3, first);
        assertEquals("ABC", new String(buffer, 0, first, StandardCharsets.UTF_8));

        int second = input.read(buffer, 0, buffer.length);
        assertEquals(3, second);
        assertEquals("DEF", new String(buffer, 0, second, StandardCharsets.UTF_8));

        assertEquals(-1, input.read(buffer, 0, buffer.length));
    }

    @Test
    public void bulkReadTakesOnlyPartOfBackBufferWhenLengthIsSmaller() throws Exception {
        ScannerInputStream input = of("");
        input.writeBack("ABCD".getBytes(StandardCharsets.UTF_8), 0, 4);

        byte[] buffer = new byte[2];
        assertEquals(2, input.read(buffer, 0, 2));
        assertEquals("AB", new String(buffer, 0, 2, StandardCharsets.UTF_8));
        //remaining pushed-back bytes stay in order across a mix of bulk and single reads
        assertEquals('C', input.read());
        assertEquals(1, input.read(buffer, 0, 1));
        assertEquals('D', buffer[0]);
        assertEquals(-1, input.read());
    }

    @Test
    public void bulkReadPreservesBytesAboveSignedRange() throws Exception {
        byte[] data = {(byte) 0x00, (byte) 0x7F, (byte) 0x80, (byte) 0xFF};
        ScannerInputStream input = new ScannerInputStream(new ByteArrayInputStream(data));
        input.writeBack(new byte[]{(byte) 0xFE}, 0, 1);

        byte[] buffer = new byte[8];
        assertEquals(1, input.read(buffer, 0, buffer.length));
        assertEquals((byte) 0xFE, buffer[0]);
        assertEquals(4, input.read(buffer, 0, buffer.length));
        assertArrayEquals(data, Arrays.copyOf(buffer, 4));
    }

    @Test
    public void availableCountsPushedBackBytes() throws Exception {
        ScannerInputStream input = of("DEF");
        assertEquals(3, input.available());

        input.writeBack("ABC".getBytes(StandardCharsets.UTF_8), 0, 3);
        assertEquals(6, input.available());

        input.read();
        assertEquals(5, input.available());
    }

    /**
     * Guards the reason the bulk override exists: a buffered copy of a raw body must
     * produce exactly the same bytes as the per-byte path used to.
     */
    @Test
    public void bulkCopyMatchesSingleByteReadsOverChunkedBaseStream() throws Exception {
        byte[] data = new byte[64 * 1024];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 31);
        }

        ByteArrayOutputStream copied = new ByteArrayOutputStream();
        //a base stream that hands out small pieces, like a socket would
        Utils.copyStream(new ScannerInputStream(new DribblingInputStream(data, 1000)), copied);

        assertArrayEquals(data, copied.toByteArray());
    }

    /**
     * Returns at most {@code maxChunk} bytes per bulk read so that short reads from the
     * base stream are covered too.
     */
    private static class DribblingInputStream extends InputStream {
        private final byte[] data;
        private final int maxChunk;
        private int position = 0;

        DribblingInputStream(byte[] data, int maxChunk) {
            this.data = data;
            this.maxChunk = maxChunk;
        }

        @Override
        public int read() {
            return position < data.length ? data[position++] & 0xff : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (position >= data.length) {
                return -1;
            }
            int count = Math.min(Math.min(len, maxChunk), data.length - position);
            System.arraycopy(data, position, b, off, count);
            position += count;
            return count;
        }
    }
}
