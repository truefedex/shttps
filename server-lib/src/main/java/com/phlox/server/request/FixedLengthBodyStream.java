package com.phlox.server.request;

import java.io.IOException;
import java.io.InputStream;

/**
 * Body framed by a Content-Length header: exactly {@code length} octets of the
 * underlying connection stream belong to this body. Reports EOF after them,
 * never reads past them and never closes the connection stream.
 * <p>
 * If the client closes the connection before sending the promised amount of data,
 * the stream reports EOF and counts as fully consumed - the connection is dead
 * at that point anyway.
 */
public class FixedLengthBodyStream extends BodyInputStream {
    private final InputStream base;
    private volatile long remaining;

    public FixedLengthBodyStream(InputStream base, long length) {
        this.base = base;
        this.remaining = Math.max(length, 0);
    }

    @Override
    public int read() throws IOException {
        if (remaining <= 0) {
            return -1;
        }
        int b = base.read();
        if (b == -1) {
            remaining = 0;
        } else {
            remaining--;
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        if (remaining <= 0) {
            return -1;
        }
        int count = base.read(b, off, (int) Math.min(len, remaining));
        if (count == -1) {
            remaining = 0;
        } else {
            remaining -= count;
        }
        return count;
    }

    @Override
    public int available() throws IOException {
        return (int) Math.min(base.available(), remaining);
    }

    public long remaining() {
        return remaining;
    }

    @Override
    public boolean isFullyConsumed() {
        return remaining <= 0;
    }

    @Override
    public void close() {
        //intentionally does not close the underlying connection stream
    }
}
