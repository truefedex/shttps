package com.phlox.simpleserver.utils;

import com.phlox.server.utils.SHTTPSLoggerProxy;

import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.IOException;

public abstract class AbstractDataStreamer {
    private final PipedInputStream pipedInputStream;
    private PipedOutputStream pipedOutputStream;
    private Thread workerThread;

    private final SHTTPSLoggerProxy.Logger logger = SHTTPSLoggerProxy.getLogger(getClass());

    public AbstractDataStreamer() {
        this(1024 * 1024);//1MB buffer
    }

    public AbstractDataStreamer(int bufferSize) {
        pipedInputStream = new PipedInputStream(bufferSize);
    }

    private void setupPipe() {
        try {
            pipedOutputStream = new PipedOutputStream(pipedInputStream);
        } catch (IOException e) {
            logger.e("Error creating pipe", e);
        }
    }

    public void startDataGenerationThread() {
        if (workerThread != null) {
            throw new IllegalStateException("Data generation thread is already started");
        }
        setupPipe();
        workerThread = new Thread(() -> {
            try {
                generateData(pipedOutputStream);
            } catch (Exception e) {
                logger.e("Error generating data", e);
            } finally {
                try {
                    pipedOutputStream.close();
                } catch (IOException e) {
                    logger.e("Error closing pipe", e);
                }
            }
        });

        workerThread.start();
    }

    protected abstract void generateData(PipedOutputStream output) throws Exception;

    public InputStream getInputStream() {
        return pipedInputStream;
    }
}

