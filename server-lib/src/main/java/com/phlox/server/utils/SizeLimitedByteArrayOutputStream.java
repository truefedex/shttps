package com.phlox.server.utils;

import java.io.ByteArrayOutputStream;

/**
 * This class is a ByteArrayOutputStream with a size limit.
 * If the limit is reached, the IllegalStateException is thrown.
 */
public class SizeLimitedByteArrayOutputStream extends ByteArrayOutputStream {
    private final int limit;

    public SizeLimitedByteArrayOutputStream(int limit) {
        this.limit = limit;
    }

    @Override
    public synchronized void write(int b) {
        if (count >= limit) {
            throw new IllegalStateException("Size limit exceeded: " + limit);
        }
        super.write(b);
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) {
        if (count + len > limit) {
            throw new IllegalStateException("Size limit exceeded: " + limit);
        }
        super.write(b, off, len);
    }
}
