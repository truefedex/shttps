package com.phlox.server.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

public class SizeLimitedByteArrayOutputStreamTest {

    @Test
    public void acceptsUpToTheLimit() {
        SizeLimitedByteArrayOutputStream out = new SizeLimitedByteArrayOutputStream(4);
        out.write(new byte[]{1, 2, 3}, 0, 3);
        out.write(4);
        assertEquals(4, out.size());
    }

    @Test
    public void rejectsBeyondTheLimit() {
        SizeLimitedByteArrayOutputStream out = new SizeLimitedByteArrayOutputStream(4);
        out.write(new byte[]{1, 2, 3, 4}, 0, 4);
        assertThrows(PayloadTooLargeException.class, () -> out.write(5));
        assertThrows(PayloadTooLargeException.class, () -> out.write(new byte[1], 0, 1));
        assertEquals(4, out.size());
    }

    @Test
    public void isStillAnIllegalStateException() {
        //what existing callers catch
        SizeLimitedByteArrayOutputStream out = new SizeLimitedByteArrayOutputStream(0);
        assertThrows(IllegalStateException.class, () -> out.write(1));
    }

    @Test
    public void hugeWriteDoesNotOverflowTheCheck() {
        SizeLimitedByteArrayOutputStream out = new SizeLimitedByteArrayOutputStream(Integer.MAX_VALUE);
        out.write(new byte[16], 0, 16);
        //count + len overflowed to a negative int and slipped past the limit check
        assertThrows(PayloadTooLargeException.class, () -> out.write(new byte[16], 0, Integer.MAX_VALUE));
    }
}
