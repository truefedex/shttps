package com.phlox.simpleserver.utils;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

public class ProgressOutputStream extends OutputStream {

    private final OutputStream out;
    private final long interval;
    private final WriteProgressListener listener;

    private long total = 0;
    private long nextTrigger = 0;
    /** Bytes already handed to {@link WriteProgressListener#onChunkWritten}. */
    private long reported = 0;
    private boolean closed = false;

    public ProgressOutputStream(OutputStream out, long interval, WriteProgressListener listener) {
        if (interval <= 0) throw new IllegalArgumentException("interval must be > 0");

        this.out = Objects.requireNonNull(out);
        this.interval = interval;
        this.listener = Objects.requireNonNull(listener);
        this.nextTrigger = interval;
    }

    /** Bytes written so far. */
    public long getTotal() {
        return total;
    }

    private void checkIntervalTrigger() throws Exception {
        if (total >= nextTrigger) {
            reportChunk();
        }
    }

    /**
     * Hands the bytes written since the last report to the listener. The report point moves
     * <b>before</b> the call: the quota listener records the chunk and then throws when the space
     * has run out, and a point moved only after a successful call made {@link #close()} report
     * the same chunk a second time - the user's used storage then stayed that much too high
     * after the failed upload had been deleted.
     */
    private void reportChunk() throws Exception {
        long chunk = total - reported;
        reported = total;
        nextTrigger = total + interval;
        listener.onChunkWritten(total, chunk);
    }

    @Override
    public void write(int b) throws IOException {
        try {
            out.write(b);
            total += 1;
            checkIntervalTrigger();
        } catch (IOException e) {
            listener.onException(e);
            throw e;
        } catch (Exception e) {
            listener.onException(e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        try {
            out.write(b, off, len);
            total += len;
            checkIntervalTrigger();
        } catch (IOException e) {
            listener.onException(e);
            throw e;
        } catch (Exception e) {
            listener.onException(e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void flush() throws IOException {
        try {
            out.flush();
        } catch (IOException e) {
            listener.onException(e);
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        //a second close must not settle the quota a second time: onStreamClosed subtracts sizes
        if (closed) return;
        closed = true;
        Exception listenerError = null;

        //the last chunk of an upload smaller than the interval is reported only here. Swallowing
        //that failure is what let a quota rejection delete the file and still answer success.
        if (total > reported) {
            try {
                reportChunk();
            } catch (Exception e) {
                listener.onException(e);
                listenerError = e;
            }
        }

        try {
            out.close();
        } catch (IOException e) {
            listener.onException(e);
            if (listenerError == null) listenerError = e;
        } finally {
            listener.onStreamClosed(total);
        }
        if (listenerError instanceof IOException) {
            throw (IOException) listenerError;
        }
        if (listenerError instanceof RuntimeException) {
            throw (RuntimeException) listenerError;
        }
        if (listenerError != null) {
            throw new RuntimeException(listenerError);
        }
    }

    public abstract static class WriteProgressListener {
        private Exception interruptedByException;
        public abstract void onChunkWritten(long totalBytes, long chunkSize) throws Exception;
        void onStreamClosed(long totalBytes) {
            onStreamClosed(totalBytes, interruptedByException);
        }
        public abstract void onStreamClosed(long totalBytes, Exception withException);
        void onException(Exception e) {
            interruptedByException = e;
        }
    }
}

