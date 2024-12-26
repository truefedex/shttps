package com.phlox.server.utils;

import java.util.logging.Handler;
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
        public void stackTrace(Throwable t) {}
    }

    public static class TaggedJavaLogger implements Logger {
        private final java.util.logging.Logger logger;
        private final int levels;

        static {
            java.util.logging.Logger.getLogger("").setLevel(java.util.logging.Level.FINE);
            java.util.logging.Logger.getLogger("").getHandlers()[0].setLevel(java.util.logging.Level.FINE);
        }

        public TaggedJavaLogger(String tag, int levels) {
            this.logger = java.util.logging.Logger.getLogger(tag);
            this.logger.addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    if (isLoggable(record)) {
                        if (record.getThrown() != null) {
                            record.getThrown().printStackTrace();
                        } else {
                            System.out.println("[" + record.getLoggerName() + "] " + record.getMessage());
                        }
                    }
                }

                @Override
                public void flush() {}

                @Override
                public void close() {}
            });
            this.logger.setUseParentHandlers(false);
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
                record.setThrown(t);
                logger.log(record);
                if ((levels & Logger.STACK_TRACE) != 0) {
                    t.printStackTrace();
                }
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
        public void stackTrace(Throwable t) {
            if ((levels & Logger.STACK_TRACE) != 0) {
                t.printStackTrace();
            }
        }
    }
}
