package com.phlox.server.utils;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

public final class SHTTPSLoggerProxy {
    private static Factory factory = tag -> new EmptyLogger();

    private SHTTPSLoggerProxy() {}

    public static void setFactory(Factory factory) {
        SHTTPSLoggerProxy.factory = factory;
    }

    public static Logger getLogger(Class<?> clazz) {
        return factory.getLogger(clazz.getSimpleName());
    }

    public static Logger getLogger(String tag) {
        return factory.getLogger(tag);
    }

    public interface Factory {
        Logger getLogger(String tag);
    }

    public interface Logger {
        int DEBUG = 1;
        int ERROR = 2;
        int INFO = 4;
        int WARNING = 8;
        int STACK_TRACE = 16;
        int ALL = DEBUG | ERROR | INFO | WARNING | STACK_TRACE;

        void d(String message);
        void e(String message);
        void e(String message, Throwable t);
        void i(String message);
        void w(String message);
        void w(String message, Throwable t);
        void stackTrace(Throwable t);
    }

    public static class EmptyLogger implements Logger {
        @Override
        public void d(String message) {}
        @Override
        public void e(String message) {}
        @Override
        public void e(String message, Throwable t) {}
        @Override
        public void i(String message) {}
        @Override
        public void w(String message) {}
        @Override
        public void w(String message, Throwable t) {}
        @Override
        public void stackTrace(Throwable t) {}
    }

    public static class TaggedJavaLogger implements Logger {
        private final java.util.logging.Logger logger;
        private final int levels;

        /** Prints records to stdout: the message, then the stack trace of the throwable if any. */
        static final class StdoutHandler extends Handler {
            @Override
            public void publish(LogRecord record) {
                if (!isLoggable(record)) {
                    return;
                }
                StringBuilder line = new StringBuilder("[").append(record.getLoggerName()).append("] ")
                        .append(record.getMessage());
                if (record.getThrown() != null) {
                    StringWriter trace = new StringWriter();
                    record.getThrown().printStackTrace(new PrintWriter(trace));
                    line.append(System.lineSeparator()).append(trace.toString().trim());
                }
                System.out.println(line);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        }

        public TaggedJavaLogger(String tag, int levels) {
            this.logger = java.util.logging.Logger.getLogger(tag);
            //the JUL logger is shared by every TaggedJavaLogger with this tag: one handler for all of
            //them, or each line comes out once per instance ever created
            synchronized (TaggedJavaLogger.class) {
                boolean hasHandler = false;
                for (Handler handler : this.logger.getHandlers()) {
                    hasHandler |= handler instanceof StdoutHandler;
                }
                if (!hasHandler) {
                    this.logger.addHandler(new StdoutHandler());
                }
            }
            this.logger.setUseParentHandlers(false);
            //which levels get through is decided by the mask below; JUL's default (INFO) would
            //silently drop every debug line on top of that
            this.logger.setLevel(Level.ALL);
            this.levels = levels;
        }

        public TaggedJavaLogger(String tag) {
            this(tag, Logger.ALL);
        }

        @Override
        public void d(String message) {
            if ((levels & Logger.DEBUG) != 0) {
                LogRecord record = new LogRecord(java.util.logging.Level.FINE, message);
                record.setSourceClassName(logger.getName());
                record.setLoggerName(logger.getName());
                logger.log(record);
            }
        }

        @Override
        public void e(String message) {
            if ((levels & Logger.ERROR) != 0) {
                LogRecord record = new LogRecord(java.util.logging.Level.SEVERE, message);
                record.setSourceClassName(logger.getName());
                record.setLoggerName(logger.getName());
                logger.log(record);
            }
        }

        @Override
        public void e(String message, Throwable t) {
            if ((levels & Logger.ERROR) != 0) {
                LogRecord record = new LogRecord(java.util.logging.Level.SEVERE, message);
                record.setSourceClassName(logger.getName());
                record.setLoggerName(logger.getName());
                if ((levels & Logger.STACK_TRACE) != 0) {
                    record.setThrown(t);
                }
                logger.log(record);
            }
        }

        @Override
        public void i(String message) {
            if ((levels & Logger.INFO) != 0) {
                LogRecord record = new LogRecord(java.util.logging.Level.INFO, message);
                record.setSourceClassName(logger.getName());
                record.setLoggerName(logger.getName());
                logger.log(record);
            }
        }

        @Override
        public void w(String message) {
            if ((levels & Logger.WARNING) != 0) {
                LogRecord record = new LogRecord(java.util.logging.Level.WARNING, message);
                record.setSourceClassName(logger.getName());
                record.setLoggerName(logger.getName());
                logger.log(record);
            }
        }

        @Override
        public void w(String message, Throwable t) {
            if ((levels & Logger.WARNING) != 0) {
                LogRecord record = new LogRecord(java.util.logging.Level.WARNING, message);
                record.setSourceClassName(logger.getName());
                record.setLoggerName(logger.getName());
                if ((levels & Logger.STACK_TRACE) != 0) {
                    record.setThrown(t);
                }
                logger.log(record);
            }
        }

        @Override
        public void stackTrace(Throwable t) {
            if ((levels & Logger.STACK_TRACE) != 0) {
                t.printStackTrace();
            }
        }
    }
}
