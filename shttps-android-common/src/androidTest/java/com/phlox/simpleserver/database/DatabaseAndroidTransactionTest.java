package com.phlox.simpleserver.database;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.phlox.simpleserver.database.model.TableData;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Transaction semantics of the Android database: what commits, what rolls back, and what happens
 * when a transaction has to be cut short.
 * <p>
 * The same ground {@code DatabaseTransactionTest} covers for the desktop implementation, but the
 * two are different enough to need both. Android transactions belong to the thread that opened
 * them and run on one shared {@link android.database.sqlite.SQLiteDatabase}, where the desktop one
 * hands each transaction its own JDBC connection - so rollback, abort and the reuse of the write
 * thread all have to be shown to work here in their own right.
 * <p>
 * Every test has a timeout, because the failure these guard against is a wedged write thread: a
 * regression should fail rather than hang.
 */
@RunWith(AndroidJUnit4.class)
public class DatabaseAndroidTransactionTest {
    private static final int TIMEOUT_MS = 30_000;

    private File dir;
    private DatabaseAndroid db;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        dir = new File(context.getCacheDir(), "dbtx-" + System.nanoTime());
        assertTrue(dir.mkdirs());
        db = new DatabaseAndroid(context, dir, "test.db");
        db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)");
    }

    @After
    public void tearDown() throws Exception {
        if (db != null) {
            try {
                db.close();
            } catch (Exception ignored) {
                //a test may have closed it already
            }
        }
        deleteRecursively(dir);
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private long rowCount() throws Exception {
        try (TableData data = db.query("SELECT COUNT(*) FROM t", null, false)) {
            data.next();
            return data.getLong(0);
        }
    }

    private static JSONObject row(String name) throws JSONException {
        return new JSONObject().put("name", name);
    }

    @Test(timeout = TIMEOUT_MS)
    public void aScopeThatReturnsNormallyIsCommitted() throws Exception {
        long id = db.runTransaction(ops -> ops.insert("t", row("kept")));
        assertTrue(id > 0);
        assertEquals(1, rowCount());
    }

    /**
     * The regression guard for handlers that used to catch their own failures inside the scope: a
     * scope that returns normally is committed, however badly things went inside it.
     */
    @Test(timeout = TIMEOUT_MS)
    public void anExceptionOutOfTheScopeRollsBackEverythingItDid() throws Exception {
        try {
            db.runTransaction(ops -> {
                ops.insert("t", row("good"));
                //NOT NULL violation
                ops.insert("t", new JSONObject().put("name", JSONObject.NULL));
                return null;
            });
            fail("the failing insert should have left the scope");
        } catch (Exception expected) {
            //the transaction is rolled back on the way out
        }
        assertEquals(0, rowCount());
    }

    @Test(timeout = TIMEOUT_MS)
    public void theFailureThatEndedAScopeReachesTheCallerUnwrapped() throws Exception {
        //it used to arrive wrapped in an ExecutionException, so callers reported that instead
        try {
            db.runTransaction(ops -> {
                throw new IllegalStateException("mine");
            });
            fail("expected the exception to propagate");
        } catch (IllegalStateException e) {
            assertEquals("mine", e.getMessage());
        }
    }

    /**
     * A scope waiting for something outside the database - which is what a transaction driven by a
     * client connection does between commands - can not notice an abort flag on its own. It has to
     * be woken, and once woken its work has to be gone and the write thread free.
     */
    @Test(timeout = TIMEOUT_MS)
    public void abortEndsAScopeThatIsWaitingAndFreesTheWriteThread() throws Exception {
        TransactionAbortHandle handle = new TransactionAbortHandle();
        CountDownLatch insertDone = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        AtomicReference<Exception> thrown = new AtomicReference<>();

        Thread caller = new Thread(() -> {
            try {
                db.runTransaction(ops -> {
                    ops.insert("t", row("written-then-aborted"));
                    insertDone.countDown();
                    //stands in for waiting on the next command of an interactive transaction
                    neverReleased.await();
                    return null;
                }, handle);
            } catch (Exception e) {
                thrown.set(e);
            }
        });
        caller.start();

        assertTrue("the scope never got going", insertDone.await(10, TimeUnit.SECONDS));
        handle.abort("deadline reached");
        caller.join(TimeUnit.SECONDS.toMillis(10));

        assertTrue("expected a TransactionAbortedException, got " + thrown.get(),
                thrown.get() instanceof TransactionAbortedException);
        assertTrue(thrown.get().getMessage().contains("deadline reached"));
        assertEquals("an aborted transaction must not leave its rows behind", 0, rowCount());

        //the whole point: the single write thread is usable again
        db.insert("t", row("after-abort"));
        assertEquals(1, rowCount());
    }

    /**
     * A scope that is working rather than waiting is stopped between operations - it must not run
     * the rest of a long batch after the abort.
     */
    @Test(timeout = TIMEOUT_MS)
    public void abortStopsAScopeBetweenItsOperations() throws Exception {
        TransactionAbortHandle handle = new TransactionAbortHandle();
        try {
            db.runTransaction(ops -> {
                ops.insert("t", row("first"));
                //an abort raised by another thread would land here; raising it from inside
                //exercises the same check without a race
                handle.abort("client went away");
                ops.insert("t", row("second"));
                return null;
            }, handle);
            fail("expected the abort to end the scope");
        } catch (TransactionAbortedException e) {
            assertTrue(e.getMessage().contains("client went away"));
        }
        assertEquals(0, rowCount());
        db.insert("t", row("after-abort"));
        assertEquals(1, rowCount());
    }

    @Test(timeout = TIMEOUT_MS)
    public void anAbortRaisedAfterTheLastOperationStillPreventsTheCommit() throws Exception {
        TransactionAbortHandle handle = new TransactionAbortHandle();
        try {
            db.runTransaction(ops -> {
                ops.insert("t", row("doomed"));
                handle.abort("too late to matter");
                return null;
            }, handle);
            fail("expected the abort to prevent the commit");
        } catch (TransactionAbortedException expected) {
            //rolled back below
        }
        assertEquals(0, rowCount());
    }

    /**
     * This implementation hands the scope the very same operations object every non-transactional
     * caller uses, so an abort must live on the per-scope wrapper. If it leaked onto the shared
     * object, one abandoned transaction would break every later caller.
     */
    @Test(timeout = TIMEOUT_MS)
    public void anAbortDoesNotLeakOntoLaterCallers() throws Exception {
        TransactionAbortHandle handle = new TransactionAbortHandle();
        try {
            db.runTransaction(ops -> {
                handle.abort("gave up");
                ops.insert("t", row("never"));
                return null;
            }, handle);
            fail("expected the abort to end the scope");
        } catch (TransactionAbortedException expected) {
            //the point is what happens next
        }

        //a plain write, and a whole further transaction, both still work
        db.insert("t", row("plain write"));
        db.runTransaction(ops -> ops.insert("t", row("later transaction")));
        assertEquals(2, rowCount());
    }

    /**
     * An abort interrupts the write thread to wake a waiting scope. That interrupt must be gone
     * before the transaction is ended and before the thread takes its next task, or
     * {@code endTransaction()} could fail and leave the shared database mid-transaction.
     */
    @Test(timeout = TIMEOUT_MS)
    public void anAbortLeavesNoInterruptBehindForTheNextTransaction() throws Exception {
        TransactionAbortHandle handle = new TransactionAbortHandle();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        Thread caller = new Thread(() -> {
            try {
                db.runTransaction(ops -> {
                    started.countDown();
                    neverReleased.await();
                    return null;
                }, handle);
            } catch (Exception ignored) {
            }
        });
        caller.start();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        handle.abort("wake up");
        caller.join(TimeUnit.SECONDS.toMillis(10));

        //if endTransaction() had been interrupted, the shared database would still be inside a
        //transaction and this would fail or hang
        db.runTransaction(ops -> ops.insert("t", row("after interrupt")));
        assertEquals(1, rowCount());
    }

    /**
     * Writes are serialized through one thread, so a write submitted from inside a scope would
     * wait for a queue only that scope can drain. It has to say so rather than hang.
     */
    @Test(timeout = TIMEOUT_MS)
    public void callingTheDatabaseFromInsideAScopeFailsInsteadOfDeadlocking() throws Exception {
        try {
            db.runTransaction(ops -> db.insert("t", row("wrong object")));
            fail("expected the re-entrant call to be refused");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("transaction scope"));
        }

        //and a nested transaction is the same mistake
        try {
            db.runTransaction(ops -> db.runTransaction(inner -> null));
            fail("expected the nested transaction to be refused");
        } catch (IllegalStateException expected) {
            //still usable afterwards
        }

        db.insert("t", row("still works"));
        assertEquals(1, rowCount());
    }

    @Test(timeout = TIMEOUT_MS)
    public void readingTheSchemaWorksInsideATransaction() throws Exception {
        //ReadSchemaOperation needs this: the rights check and the schema read share one transaction
        String name = db.runTransaction(ops -> ops.getTables()[0].name);
        assertEquals("t", name);
    }

    @Test(timeout = TIMEOUT_MS)
    public void closeShutsDownTheWriteThread() throws Exception {
        db.insert("t", row("before close"));
        db.close();
        //every database swap used to leave its write thread running for the life of the process
        try {
            db.insert("t", row("after close"));
            fail("expected the closed database to refuse further writes");
        } catch (Exception expected) {
            assertNotNull(expected);
        }
    }
}
