package com.phlox.simpleserver.test;

import com.phlox.server.database.DatabaseFabricImpl;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.TransactionAbortHandle;
import com.phlox.simpleserver.database.TransactionAbortedException;
import com.phlox.simpleserver.database.model.TableData;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Transaction semantics of the real database: what commits, what rolls back, and what happens when
 * a transaction has to be cut short.
 * <p>
 * Everything here has a timeout, because the failure mode these guard against is a wedged write
 * thread - a regression should fail the build rather than hang it.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class DatabaseTransactionTest {

    private Database newDatabase() throws Exception {
        File dir = Files.createTempDirectory("dbtx").toFile();
        File dbFile = new File(dir, "test.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT NOT NULL)");
        }
        return new DatabaseFabricImpl().openDatabase(dbFile.getAbsolutePath());
    }

    private long rowCount(Database db) throws Exception {
        try (TableData data = db.query("SELECT COUNT(*) FROM t", null, false)) {
            data.next();
            return data.getLong(0);
        }
    }

    private static JSONObject row(String name) {
        return new JSONObject().put("name", name);
    }

    /**
     * A scope waiting for something outside the database - which is what a transaction driven by a
     * client connection does between commands - can not notice an abort flag on its own. It has to
     * be woken, and once woken its work has to be gone and the write thread free.
     */
    @Test
    public void abortEndsAScopeThatIsWaitingAndFreesTheWriteThread() throws Exception {
        try (Database db = newDatabase()) {
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

            assertTrue(insertDone.await(10, TimeUnit.SECONDS), "the scope never got going");
            handle.abort("deadline reached");
            caller.join(TimeUnit.SECONDS.toMillis(10));

            assertTrue(thrown.get() instanceof TransactionAbortedException,
                    "expected a TransactionAbortedException, got " + thrown.get());
            assertTrue(thrown.get().getMessage().contains("deadline reached"));
            assertEquals(0, rowCount(db), "an aborted transaction must not leave its rows behind");

            //the whole point: the single write thread is usable again
            db.insert("t", row("after-abort"));
            assertEquals(1, rowCount(db));
        }
    }

    /**
     * A scope that is working rather than waiting is stopped between operations - it must not run
     * the rest of a long batch after the abort.
     */
    @Test
    public void abortStopsAScopeBetweenItsOperations() throws Exception {
        try (Database db = newDatabase()) {
            TransactionAbortHandle handle = new TransactionAbortHandle();
            TransactionAbortedException e = assertThrows(TransactionAbortedException.class,
                    () -> db.runTransaction(ops -> {
                        ops.insert("t", row("first"));
                        //an abort raised by another thread would land here; raising it from inside
                        //exercises the same check without a race
                        handle.abort("client went away");
                        ops.insert("t", row("second"));
                        return null;
                    }, handle));
            assertTrue(e.getMessage().contains("client went away"));
            assertEquals(0, rowCount(db));
            db.insert("t", row("after-abort"));
            assertEquals(1, rowCount(db));
        }
    }

    @Test
    public void anAbortRaisedAfterTheLastOperationStillPreventsTheCommit() throws Exception {
        try (Database db = newDatabase()) {
            TransactionAbortHandle handle = new TransactionAbortHandle();
            assertThrows(TransactionAbortedException.class,
                    () -> db.runTransaction(ops -> {
                        ops.insert("t", row("doomed"));
                        handle.abort("too late to matter");
                        return null;
                    }, handle));
            assertEquals(0, rowCount(db));
        }
    }

    /**
     * The regression guard for handlers that used to catch their own failures inside the scope:
     * a scope that returns normally is committed, however badly things went inside it.
     */
    @Test
    public void anExceptionOutOfTheScopeRollsBackEverythingItDid() throws Exception {
        try (Database db = newDatabase()) {
            assertThrows(Exception.class, () -> db.runTransaction(ops -> {
                ops.insert("t", row("good"));
                ops.insert("t", new JSONObject().put("name", JSONObject.NULL));//NOT NULL violation
                return null;
            }));
            assertEquals(0, rowCount(db), "the first insert should have been rolled back too");
        }
    }

    @Test
    public void aScopeThatReturnsNormallyIsCommitted() throws Exception {
        try (Database db = newDatabase()) {
            long id = db.runTransaction(ops -> ops.insert("t", row("kept")));
            assertTrue(id > 0);
            assertEquals(1, rowCount(db));
        }
    }

    @Test
    public void theFailureThatEndedAScopeReachesTheCallerUnwrapped() throws Exception {
        //it used to arrive wrapped in an ExecutionException, so callers reported that instead
        try (Database db = newDatabase()) {
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> db.runTransaction(ops -> {
                        throw new IllegalStateException("mine");
                    }));
            assertEquals("mine", thrown.getMessage());
        }
    }

    /**
     * Writes are serialized through one thread, so a write submitted from inside a scope would wait
     * for a queue only that scope can drain. It has to say so rather than hang.
     */
    @Test
    public void callingTheDatabaseFromInsideAScopeFailsInsteadOfDeadlocking() throws Exception {
        try (Database db = newDatabase()) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> db.runTransaction(ops -> db.insert("t", row("wrong object"))));
            assertTrue(e.getMessage().contains("transaction scope"), e.getMessage());

            //and a nested transaction is the same mistake
            assertThrows(IllegalStateException.class,
                    () -> db.runTransaction(ops -> db.runTransaction(inner -> null)));

            db.insert("t", row("still works"));
            assertEquals(1, rowCount(db));
        }
    }

    @Test
    public void readingTheSchemaWorksInsideATransaction() throws Exception {
        //ReadSchemaOperation needs this: the rights check and the schema read share one transaction
        try (Database db = newDatabase()) {
            String name = db.runTransaction(ops -> ops.getTables()[0].name);
            assertEquals("t", name);
        }
    }

    @Test
    public void aRolledBackTableCreationLeavesNoTableBehind() throws Exception {
        //DDL is transactional in sqlite, which is what makes a batch of schema changes all-or-nothing
        try (Database db = newDatabase()) {
            assertThrows(Exception.class, () -> db.runTransaction(ops -> {
                ops.query("CREATE TABLE created_then_dropped (x INTEGER)");
                throw new IllegalStateException("changed my mind");
            }));
            assertEquals(1, db.getTables().length);
        }
    }

    @Test
    public void closeShutsDownTheWriteThread() throws Exception {
        Database db = newDatabase();
        db.insert("t", row("before close"));
        db.close();
        //every database swap used to leave its write thread running for the life of the process
        assertNotNull(assertThrows(Exception.class, () -> db.insert("t", row("after close"))));
    }
}
