package com.phlox.simpleserver.database;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Shared plumbing for the {@link Database} implementations, which all serialize writes through a
 * single-thread executor.
 */
public final class DatabaseExecutorSupport {
    private DatabaseExecutorSupport() {
    }

    /**
     * Waits for a task submitted to the write executor and rethrows whatever it threw, unwrapped.
     * <p>
     * Without this, everything a transaction scope throws reaches the caller wrapped in an
     * {@link ExecutionException}, and each call site has to peel it off - which only
     * {@code DBInsertRequestHandler} ever did, so every other caller reported
     * "java.util.concurrent.ExecutionException: ..." to the client. Unwrapping here means callers
     * catch the exception they actually threw.
     */
    public static <T> T await(Future<T> future) throws Exception {
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw e;
        }
    }
}
