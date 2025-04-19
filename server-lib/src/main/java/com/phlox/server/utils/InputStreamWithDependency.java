package com.phlox.server.utils;

import java.io.IOException;
import java.io.InputStream;

public class InputStreamWithDependency extends InputStream {
    private final InputStream delegate;
    private final AutoCloseable dependency;

    public InputStreamWithDependency(InputStream delegate, AutoCloseable dependency) {
        this.delegate = delegate;
        this.dependency = dependency;
    }

    @Override
    public int read() throws IOException {
        return delegate.read();
    }

    @Override
    public void close() throws IOException {
        try {
            delegate.close();
        } finally {
            try {
                dependency.close();
            } catch (Exception e) {
                throw new IOException("Failed to close dependent resource", e);
            }
        }
    }

    @Override
    public int available() throws IOException {
        return delegate.available();
    }

    @Override
    public synchronized void mark(int readlimit) {
        delegate.mark(readlimit);
    }

    @Override
    public boolean markSupported() {
        return delegate.markSupported();
    }

    @Override
    public int read(byte[] b) throws IOException {
        return delegate.read(b);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return delegate.read(b, off, len);
    }

    @Override
    public synchronized void reset() throws IOException {
        delegate.reset();
    }

    @Override
    public long skip(long n) throws IOException {
        return delegate.skip(n);
    }
}
