package com.phlox.simpleserver.database.operations;

import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.database.FakeDatabaseOperations;
import com.phlox.simpleserver.database.FakeTableData;
import com.phlox.simpleserver.database.TestDBEnvironment;
import com.phlox.simpleserver.database.model.TableData;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CustomSqlOperationTest {
    private TestDBEnvironment env;
    private FakeDatabaseOperations db;

    @BeforeEach
    public void setUp() {
        env = new TestDBEnvironment();
        db = new FakeDatabaseOperations();
    }

    @Test
    public void runsASingleStatementAndReturnsItsRows() throws Exception {
        db.queryResult = FakeTableData.of(new String[]{"id"}, new Object[]{1});
        TableData result = run("SELECT * FROM t", TestDBEnvironment.userWith(User.DBRights.EXEC_SQL));
        assertSame(db.queryResult, result);
        assertEquals(1, db.calls.size());
        assertEquals("query:SELECT * FROM t", db.calls.get(0));
    }

    @Test
    public void runsEveryStatementOfABatchInOrder() throws Exception {
        run("CREATE TABLE a(x); INSERT INTO a VALUES(1); SELECT * FROM a",
                TestDBEnvironment.userWith(User.DBRights.EXEC_SQL));
        assertEquals(3, db.calls.size());
        assertEquals("query:CREATE TABLE a(x)", db.calls.get(0));
        assertEquals("query:SELECT * FROM a", db.calls.get(2));
    }

    @Test
    public void closesTheResultsOfEveryStatementButTheLast() throws Exception {
        //only the last statement's rows are handed to the caller; the rest would leak
        FakeTableData shared = FakeTableData.of(new String[]{"x"}, new Object[]{1});
        db.queryResult = shared;
        run("SELECT 1; SELECT 2", TestDBEnvironment.userWith(User.DBRights.EXEC_SQL));
        assertTrue(shared.isClosed());
    }

    @Test
    public void needsTheExecSqlRight() {
        assertKind(DBOperationException.Kind.FORBIDDEN,
                () -> run("SELECT 1", TestDBEnvironment.userWith(User.DBRights.READ, User.DBRights.UPDATE)));
        assertTrue(db.calls.isEmpty(), "no SQL may run when the check failed, got " + db.calls);
    }

    @Test
    public void refusesWhenThereIsNoAuthenticatedUserAndAuthIsOn() {
        assertKind(DBOperationException.Kind.FORBIDDEN, () -> run("SELECT 1", null));
    }

    @Test
    public void allowsAnythingWhenAuthenticationIsOff() throws Exception {
        env.config.setAuthMode(SHTTPSConfig.AuthMode.NONE);
        run("SELECT 1", null);
        assertEquals(1, db.calls.size());
    }

    @Test
    public void refusesWhenTheCustomSqlApiIsSwitchedOff() {
        env.config.setAllowDatabaseCustomSqlRemoteApi(false);
        assertKind(DBOperationException.Kind.DISABLED,
                () -> run("SELECT 1", TestDBEnvironment.userWith(User.DBRights.EXEC_SQL)));
        assertTrue(db.calls.isEmpty());
    }

    @Test
    public void rejectsEmptySql() {
        assertKind(DBOperationException.Kind.BAD_REQUEST,
                () -> run(null, TestDBEnvironment.userWith(User.DBRights.EXEC_SQL)));
        assertKind(DBOperationException.Kind.BAD_REQUEST,
                () -> run("   \n ", TestDBEnvironment.userWith(User.DBRights.EXEC_SQL)));
    }

    @Test
    public void reportsSqlTheDatabaseRejectedAsAFailedOperation() {
        //the client's SQL was wrong, which is a different thing from the server malfunctioning
        db.queryResult = null;
        FakeDatabaseOperations failing = new FakeDatabaseOperations() {
            @Override
            public TableData query(String query, Object[] args, boolean possiblyWriteOperation) {
                throw new IllegalStateException("no such column: nope");
            }
        };
        DBOperationException e = assertThrows(DBOperationException.class,
                () -> new CustomSqlOperation(env.config, env.authManager,
                        new CustomSqlOperation.Params("SELECT nope"))
                        .execute(failing, TestDBEnvironment.userWith(User.DBRights.EXEC_SQL)));
        assertEquals(DBOperationException.Kind.FAILED, e.kind);
        assertTrue(e.getMessage().contains("no such column"));
    }

    private TableData run(String sql, User user) throws Exception {
        return new CustomSqlOperation(env.config, env.authManager,
                new CustomSqlOperation.Params(sql)).execute(db, user);
    }

    private interface Action {
        void run() throws Exception;
    }

    private static void assertKind(DBOperationException.Kind kind, Action action) {
        DBOperationException e = assertThrows(DBOperationException.class, action::run);
        assertEquals(kind, e.kind);
    }
}
