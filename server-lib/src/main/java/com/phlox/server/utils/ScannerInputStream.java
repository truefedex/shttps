package com.phlox.server.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;

public class ScannerInputStream extends InputStream {
    /** Thrown when a line or a delimited section is longer than the caller allows. */
    public static class LimitExceededException extends IOException {
        public LimitExceededException(String message) {
            super(message);
        }
    }

    InputStream base;
    byte[] backBuff = new byte[16];
    int backBuffPosition = -1;
    //deadline for single-byte reads (all line parsing); armed by the first byte read after
    //startDeadlineOnNextByte. System.nanoTime() may be negative, hence the separate flag
    private boolean deadlineArmed = false;
    private long deadlineNanos;
    private long pendingDeadlineTimeoutNanos = 0;

    public ScannerInputStream(InputStream base) {
        this.base = base;
    }

    @Override
    public int read() throws IOException {
        int b;
        if (backBuffPosition == -1) {
            b = base.read();
        } else {
            b = backBuff[backBuffPosition--] & 0xff;
        }
        if (b != -1) {
            checkDeadline();
        }
        return b;
    }

    /**
     * Limits how long the next section read byte by byte (a request head) may take as a whole,
     * counted from its first byte - so waiting for a request to start, which is what an idle
     * keep-alive connection does, does not count. A per-read socket timeout alone does not stop
     * a client that trickles one byte at a time. Once expired, single-byte reads throw
     * {@link SocketTimeoutException}; bulk reads (request bodies) are not affected.
     */
    public void startDeadlineOnNextByte(long timeoutMillis) {
        deadlineArmed = false;
        pendingDeadlineTimeoutNanos = timeoutMillis > 0 ? timeoutMillis * 1_000_000L : 0;
    }

    public void clearDeadline() {
        deadlineArmed = false;
        pendingDeadlineTimeoutNanos = 0;
    }

    private void checkDeadline() throws SocketTimeoutException {
        if (pendingDeadlineTimeoutNanos != 0) {
            deadlineNanos = System.nanoTime() + pendingDeadlineTimeoutNanos;
            pendingDeadlineTimeoutNanos = 0;
            deadlineArmed = true;
        } else if (deadlineArmed && System.nanoTime() - deadlineNanos > 0) {
            deadlineArmed = false;
            throw new SocketTimeoutException("Deadline for reading the request head exceeded");
        }
    }

    /**
     * Bulk read. Without this override java.io.InputStream falls back to a per-byte
     * loop, which makes every raw request body (PUT, WebDAV PUT, non-form POST) cost
     * one call per byte instead of one per buffer. Note that pushed-back bytes are
     * served first and are never mixed with fresh data from the base stream, so this
     * may return a short read - which is legal and expected by callers.
     */
    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        if (backBuffPosition == -1) {
            return base.read(b, off, len);
        }
        //back buffer holds bytes in reverse order, top of the "stack" comes out first
        int count = Math.min(len, backBuffPosition + 1);
        for (int i = 0; i < count; i++) {
            b[off + i] = backBuff[backBuffPosition--];
        }
        return count;
    }

    @Override
    public int available() throws IOException {
        return (backBuffPosition + 1) + base.available();
    }

    //package-private instead of private so that tests can push bytes back directly:
    //the parsing loops below always drain the back buffer before they return, so there
    //is no public API that leaves it non-empty
    void writeBack(byte[] buf, int offset, int len) {
        int backBuffDataSize = backBuffPosition + 1;
        if (backBuff.length < (len + backBuffDataSize)) {
            byte[] newArray = new byte[len + backBuffDataSize];
            if (backBuffPosition != -1) {
                System.arraycopy(backBuff, 0, newArray, 0, backBuffDataSize);
            }
            backBuff = newArray;
        }
        for (int i = len + offset - 1; i >= offset; i--) {
            backBuffPosition++;
            backBuff[backBuffPosition] = buf[i];
        }
    }

    public boolean readUntilDelimiter(byte[] delimiter, OutputStream output) throws IOException {
        return readUntilDelimiter(delimiter, output, Long.MAX_VALUE);
    }

    /**
     * Copies bytes to {@code output} until {@code delimiter}, which is consumed but not copied.
     *
     * @param maxLength how many bytes may be copied before the delimiter is found
     * @return false if the stream ended before the delimiter
     * @throws LimitExceededException when more than {@code maxLength} bytes precede the delimiter
     */
    public boolean readUntilDelimiter(byte[] delimiter, OutputStream output, long maxLength) throws IOException {
        long copied = 0;
        byte[] collector = new byte[delimiter.length];
        int readenByte;
        int collectorPosition = 0;
        boolean boundaryFound = false;
        while ((readenByte = read()) != -1) {
            if (readenByte == delimiter[collectorPosition]) {
                collector[collectorPosition] = (byte) readenByte;
                collectorPosition++;
                if (collectorPosition == collector.length) {
                    boundaryFound = true;
                    break;
                }
            } else if (collectorPosition > 0) {
                //collected bytes are not boundary but the boundary may still start somewhere
                //in the middle of the collector so we should return back and check
                collector[collectorPosition] = (byte) readenByte;//add wrong byte also to collector just for convenience to copy all back
                if (++copied > maxLength) {
                    throw new LimitExceededException("No delimiter within " + maxLength + " bytes");
                }
                output.write(collector[0]);
                writeBack(collector, 1, collectorPosition);
                collectorPosition = 0;
            } else {
                if (++copied > maxLength) {
                    throw new LimitExceededException("No delimiter within " + maxLength + " bytes");
                }
                output.write(readenByte);
            }
        }
        return boundaryFound;
    }

    public String nextLine() throws IOException {
        return nextLine("ASCII");
    }

    public String nextLine(String charset) throws IOException {
        return nextLine(charset, Long.MAX_VALUE);
    }

    /**
     * @param maxLength longest line accepted, CRLF not counted
     * @return the line without its CRLF, or null if the stream ended before any byte of it
     * @throws LimitExceededException for a longer line
     */
    public String nextLine(String charset, long maxLength) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean boundaryFound = readUntilDelimiter(new byte[]{0x0D, 0x0A}, output, maxLength);
        String result = output.toString(charset);
        return ("".equals(result) && !boundaryFound) ? null : result;
    }
}
