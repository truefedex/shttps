package com.phlox.simpleserver.utils;

import com.phlox.server.utils.SHTTPSLoggerProxy;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class AbstractDataStreamer {
    private Pipe pipe;
    private Pipe.SinkChannel sinkChannel;
    private Pipe.SourceChannel sourceChannel;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final int bufferSize;

    private final SHTTPSLoggerProxy.Logger logger = SHTTPSLoggerProxy.getLogger(getClass());

    public AbstractDataStreamer() {
        this(2 * 1024 * 1024); // 2MB buffer - increased from 1MB
    }

    public AbstractDataStreamer(int bufferSize) {
        this.bufferSize = bufferSize;
    }

    private void setupPipe() throws IOException {
        pipe = Pipe.open();
        sinkChannel = pipe.sink();
        sourceChannel = pipe.source();
        
        // Configure channels for better performance
        sinkChannel.configureBlocking(true); // Keep blocking for simplicity
        sourceChannel.configureBlocking(true);
    }

    protected void executeInBackground(Runnable runnable) {
        new Thread(runnable, "DataStreamer-" + getClass().getSimpleName()).start();
    }

    public void startDataGenerationThread() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Data generation thread is already started");
        }
        
        try {
            setupPipe();
        } catch (IOException e) {
            logger.e("Error creating pipe", e);
            started.set(false);
            throw new RuntimeException("Failed to setup pipe", e);
        }
        
        executeInBackground(() -> {
            try {
                // Create a WritableByteChannel wrapper for the sink
                WritableByteChannel outputChannel = new WritableByteChannel() {
                    @Override
                    public int write(ByteBuffer src) throws IOException {
                        return sinkChannel.write(src);
                    }

                    @Override
                    public boolean isOpen() {
                        return sinkChannel.isOpen();
                    }

                    @Override
                    public void close() throws IOException {
                        sinkChannel.close();
                    }
                };
                
                // Create OutputStream adapter for the channel
                OutputStream output = Channels.newOutputStream(outputChannel);
                generateData(output);
            } catch (Exception e) {
                logger.e("Error generating data", e);
            } finally {
                try {
                    if (sinkChannel != null && sinkChannel.isOpen()) {
                        sinkChannel.close();
                    }
                } catch (IOException e) {
                    logger.e("Error closing sink channel", e);
                }
            }
        });
    }

    protected abstract void generateData(OutputStream output) throws Exception;

    public InputStream getInputStream() {
        if (!started.get()) {
            throw new IllegalStateException("Data generation thread has not been started");
        }
        
        // Create a ReadableByteChannel wrapper for the source
        return Channels.newInputStream(sourceChannel);
    }
}

