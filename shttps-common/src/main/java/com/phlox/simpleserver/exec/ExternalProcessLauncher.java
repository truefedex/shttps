package com.phlox.simpleserver.exec;

import com.phlox.server.request.Request;
import com.phlox.server.responses.Response;
import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.server.utils.Utils;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.utils.AbstractDataStreamer;

import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;

public abstract class ExternalProcessLauncher {

    /**
     * Destroys the process forcibly when the runtime supports it (Java 8+ / Android API 26+),
     * otherwise calls {@link Process#destroy()}. Uses reflection so the same code works on
     * Android with min API &lt; 26 where {@link Process#destroyForcibly()} is not available.
     */
    protected static void destroyProcessForcibly(Process process) {
        try {
            Method method = Process.class.getMethod("destroyForcibly");
            method.invoke(process);
        } catch (Exception e) {
            process.destroy();
        }
    }

    /**
     * Starts a daemon thread that reads the process's stderr and logs each line to the given logger.
     * Call this instead of {@link ProcessBuilder#redirectErrorStream(boolean) redirectErrorStream(true)}.
     */
    protected static void startStderrLoggerThread(Process process, SHTTPSLoggerProxy.Logger logger, String threadName) {
        InputStream err = process.getErrorStream();
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(err))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.w(line);
                }
            } catch (Exception e) {
                logger.stackTrace(e);
            }
        }, threadName);
        t.setDaemon(true);
        t.start();
    }
    protected final File cgiFolder;
    protected final SHTTPSConfig config;

    public ExternalProcessLauncher(File cgiFolder, SHTTPSConfig config) {
        this.cgiFolder = cgiFolder;
        this.config = config;
    }

    /**
     * Launches an external process for the given request.
     * @param executionTimeout Max time in milliseconds before the process is forcibly destroyed; null for no limit.
     */
    abstract Response launch(Request request, String executeWith, File cgiScriptFile, User user,
                            @Nullable Integer executionTimeout) throws Exception;

    protected static class ProcessDataStreamer extends AbstractDataStreamer {
        private final Process process;
        private final InputStream processInputStream;

        public ProcessDataStreamer(Process process, InputStream processInputStream) {
            super();
            this.process = process;
            this.processInputStream = processInputStream;
        }

        @Override
        protected void generateData(OutputStream output) throws Exception {
            try {
                // Copy remaining data from process input stream to output
                Utils.copyStream(processInputStream, output);
            } finally {
                // Wait for process to complete and cleanup
                try {
                    process.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
