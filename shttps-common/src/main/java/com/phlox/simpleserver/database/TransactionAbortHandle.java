package com.phlox.simpleserver.database;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Lets a thread other than the one running a transaction end it early, with a guaranteed rollback.
 * <p>
 * Every {@link Database} implementation runs transactions on a single write thread and blocks the
 * caller until the scope returns, so a scope that never finishes stops writes for every other
 * client. That is tolerable while a scope is only ever a handful of statements, but not once a
 * scope can wait for input - a transaction driven over a connection has to be bounded by a deadline
 * and by the health of that connection, and something has to be able to cut it short.
 * <p>
 * A scope can be stuck in two different ways, so aborting has two halves:
 * <ul>
 *     <li><b>executing statements</b> - the {@link DatabaseOperations} handed to the scope is
 *     wrapped so that every call checks {@link #isAborted()} first and throws
 *     {@link TransactionAbortedException}. This is what stops a long batch of statements between
 *     two of them.</li>
 *     <li><b>waiting</b> - a scope parked on a queue or a lock never calls the database, so no
 *     check would ever run. {@link #abort} therefore also interrupts the thread executing the
 *     scope, turning the wait into an {@link InterruptedException}.</li>
 * </ul>
 * Either way the exception leaves the scope and reaches the implementation's rollback path, and
 * {@code runTransaction} throws {@link TransactionAbortedException} once the rollback is done.
 * <p>
 * What this can <b>not</b> do is interrupt a database call that is already in flight - a long
 * running statement runs to its end, and the abort takes effect immediately afterwards. Cancelling
 * mid-statement would need the driver's own statement cancellation and is not supported.
 * <p>
 * Instances are single-use and thread-safe. {@link #abort} may be called any number of times, from
 * any thread, before or after the transaction starts; an abort raised before the scope begins takes
 * effect at its first database call.
 */
public class TransactionAbortHandle {
    private boolean aborted = false;
    private @Nullable String reason = null;
    /** The thread executing the scope, while there is one. */
    private @Nullable Thread worker = null;

    public synchronized boolean isAborted() {
        return aborted;
    }

    public synchronized @Nullable String getReason() {
        return reason;
    }

    /**
     * Ends the transaction as soon as it can be ended: rolled back, never committed. Callable from
     * any thread and at any time. Returns without waiting for the rollback to happen - the thread
     * that called {@code runTransaction} is the one that sees it through.
     *
     * @param reason kept for the {@link TransactionAbortedException} message; the first one wins
     */
    public synchronized void abort(@Nullable String reason) {
        if (!aborted) {
            this.aborted = true;
            this.reason = reason;
        }
        if (worker != null) {
            //wakes a scope that is waiting rather than working; harmless if it is neither
            worker.interrupt();
        }
    }

    /**
     * Throws if this transaction has been aborted. Called by the guarding {@link DatabaseOperations}
     * wrapper before each operation.
     */
    public synchronized void checkNotAborted() throws TransactionAbortedException {
        if (aborted) {
            throw new TransactionAbortedException(reason);
        }
    }

    /**
     * Called by a {@link Database} implementation on the write thread, before the scope starts.
     * If an abort already arrived, the interrupt is delivered right away so a scope that begins by
     * waiting does not wait forever.
     */
    public synchronized void attachWorker(@NotNull Thread thread) {
        this.worker = thread;
        if (aborted) {
            thread.interrupt();
        }
    }

    /**
     * Called by a {@link Database} implementation once the scope is over, on the same thread that
     * called {@link #attachWorker}. Clears the thread's interrupt status so that an abort delivered
     * late can not leak onto the next task to run on a shared write thread.
     */
    public synchronized void detachWorker() {
        this.worker = null;
        Thread.interrupted();
    }
}
