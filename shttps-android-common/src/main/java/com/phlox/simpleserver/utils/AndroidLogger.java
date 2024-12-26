package com.phlox.simpleserver.utils;

import android.util.Log;

import com.phlox.server.utils.SHTTPSLoggerProxy;

public class AndroidLogger implements SHTTPSLoggerProxy.Logger {
    private final String tag;
    private final int logLevels;

    public AndroidLogger(String tag, int logLevels) {
        this.tag = tag;
        this.logLevels = logLevels;
    }

    @Override
    public void d(String message) {
        if ((logLevels & SHTTPSLoggerProxy.Logger.DEBUG) != 0) {
            Log.d(tag, message);
        }
    }

    @Override
    public void e(String message) {
        if ((logLevels & SHTTPSLoggerProxy.Logger.ERROR) != 0) {
            Log.e(tag, message);
        }
    }

    @Override
    public void e(String message, Throwable t) {
        if ((logLevels & SHTTPSLoggerProxy.Logger.ERROR) != 0) {
            Log.e(tag, message, t);
        }
    }

    @Override
    public void i(String message) {
        if ((logLevels & SHTTPSLoggerProxy.Logger.INFO) != 0) {
            Log.i(tag, message);
        }
    }

    @Override
    public void w(String message) {
        if ((logLevels & SHTTPSLoggerProxy.Logger.WARNING) != 0) {
            Log.w(tag, message);
        }
    }

    @Override
    public void stackTrace(Throwable t) {
        if ((logLevels & SHTTPSLoggerProxy.Logger.STACK_TRACE) != 0) {
            Log.e(tag, Log.getStackTraceString(t));
        }
    }
}
