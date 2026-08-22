package com.phlox.simpleserver.database;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard the transaction scope actually works through.
 */
public class AbortableDatabaseOperationsTest {

    @Test
    public void passesEverythingThroughWhileTheTransactionIsWanted() throws Exception {
        FakeDatabaseOperations delegate = new FakeDatabaseOperations();
        DatabaseOperations ops = new AbortableDatabaseOperations(delegate, new TransactionAbortHandle());

        ops.insert("t", new JSONObject().put("a", 1));
        ops.update("t", new JSONObject(), null, null);
        ops.delete("t", null, null);
        ops.query("SELECT 1");
        ops.getTables();

        assertEquals(5, delegate.calls.size());
    }

    @Test
    public void refusesEveryOperationOnceAborted() throws Exception {
        FakeDatabaseOperations delegate = new FakeDatabaseOperations();
        TransactionAbortHandle handle = new TransactionAbortHandle();
        DatabaseOperations ops = new AbortableDatabaseOperations(delegate, handle);

        ops.insert("t", new JSONObject().put("a", 1));
        handle.abort("done with you");

        assertThrows(TransactionAbortedException.class, () -> ops.insert("t", new JSONObject()));
        assertThrows(TransactionAbortedException.class, () -> ops.update("t", new JSONObject(), null, null));
        assertThrows(TransactionAbortedException.class, () -> ops.delete("t", null, null));
        assertThrows(TransactionAbortedException.class, () -> ops.query("SELECT 1"));
        assertThrows(TransactionAbortedException.class, () -> ops.execute("SELECT 1"));
        assertThrows(TransactionAbortedException.class, () -> ops.getTables());
        assertThrows(TransactionAbortedException.class, () -> ops.getTableDataSecure("t"));
        assertThrows(TransactionAbortedException.class,
                () -> ops.getSingleCellDataStream("t", "c", null, null));

        assertEquals(1, delegate.calls.size(), "nothing may reach the database after an abort");
    }

    /**
     * The Android implementation hands its transaction scope the very same operations object that
     * every non-transactional caller uses. Abort state therefore has to live on the wrapper, not on
     * what it wraps, or one aborted transaction would break unrelated callers.
     */
    @Test
    public void abortingOneScopeLeavesTheWrappedOperationsUsable() throws Exception {
        FakeDatabaseOperations shared = new FakeDatabaseOperations();

        TransactionAbortHandle aborted = new TransactionAbortHandle();
        DatabaseOperations first = new AbortableDatabaseOperations(shared, aborted);
        aborted.abort("first scope gave up");
        assertThrows(TransactionAbortedException.class, () -> first.insert("t", new JSONObject()));

        //the shared object itself is untouched, so a later scope - and the plain, unwrapped path -
        //still work
        DatabaseOperations second = new AbortableDatabaseOperations(shared, new TransactionAbortHandle());
        second.insert("t", new JSONObject().put("a", 2));
        shared.insert("t", new JSONObject().put("a", 3));
        assertEquals(2, shared.calls.size());
    }

    @Test
    public void anAbortRaisedBeforeTheScopeStartsStopsItsFirstOperation() {
        FakeDatabaseOperations delegate = new FakeDatabaseOperations();
        TransactionAbortHandle handle = new TransactionAbortHandle();
        handle.abort("never mind");
        DatabaseOperations ops = new AbortableDatabaseOperations(delegate, handle);

        assertThrows(TransactionAbortedException.class, () -> ops.query("SELECT 1"));
        assertTrue(delegate.calls.isEmpty());
    }

    @Test
    public void theFirstReasonIsTheOneReported() {
        TransactionAbortHandle handle = new TransactionAbortHandle();
        handle.abort("deadline reached");
        handle.abort("connection dropped");
        assertEquals("deadline reached", handle.getReason());
    }

    @Test
    public void detachingClearsALateInterruptSoItCannotHitTheNextTask() {
        //the write thread is shared; an interrupt left set would land on whatever runs next
        TransactionAbortHandle handle = new TransactionAbortHandle();
        handle.attachWorker(Thread.currentThread());
        handle.abort("wake up");
        assertTrue(Thread.currentThread().isInterrupted());
        handle.detachWorker();
        assertTrue(!Thread.currentThread().isInterrupted(), "the interrupt should have been cleared");
    }
}
