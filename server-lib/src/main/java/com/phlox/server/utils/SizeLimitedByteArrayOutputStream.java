package com.phlox.server.utils;

import java.io.ByteArrayOutputStream;

/**
 * This class is a ByteArrayOutputStream with a size limit.
 * If the limit is reached, a {@link PayloadTooLargeException} is thrown.
 */
public class SizeLimitedByteArrayOutputStream extends ByteArrayOutputStream {
    private final int limit;

    public SizeLimitedByteArrayOutputStream(int limit) {
        this.limit = limit;
    }

    @Override
    public synchronized void write(int b) {
        if (count >= limit) {
            throw new PayloadTooLargeException("Size limit exceeded: " + limit);
        }
        super.write(b);
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) {
        //long arithmetic: count + len overflows an int near the top of the range
        if ((long) count + len > limit) {
            throw new PayloadTooLargeException("Size limit exceeded: " + limit);
        }
        super.write(b, off, len);
    }
}
