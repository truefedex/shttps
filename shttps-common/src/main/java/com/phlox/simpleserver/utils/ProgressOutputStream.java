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

    public ProgressOutputStream(OutputStream out, long interval, WriteProgressListener listener) {
        if (interval <= 0) throw new IllegalArgumentException("interval must be > 0");

        this.out = Objects.requireNonNull(out);
        this.interval = interval;
        this.listener = Objects.requireNonNull(listener);
        this.nextTrigger = interval;
    }

    private void checkIntervalTrigger() throws Exception {
        if (total >= nextTrigger) {
            listener.onChunkWritten(total, total - (nextTrigger - interval));
            nextTrigger = total + interval;
        }
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
        long lastIntervalPoint = nextTrigger - interval;

        if (total > lastIntervalPoint) {
            try {
                listener.onChunkWritten(total, total - lastIntervalPoint);
            } catch (Exception e) {
                listener.onException(e);
                //ignoring exceptions from callback on stream close
            }
        }

        try {
            out.close();
        } catch (IOException e) {
            listener.onException(e);
            throw e;
        } finally {
            listener.onStreamClosed(total);
        }
    }

    public interface WriteProgressListener {
        void onChunkWritten(long totalBytes, long chunkSize) throws Exception;
        void onStreamClosed(long totalBytes);
        void onException(Exception e);
    }
}

