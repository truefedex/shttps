package com.phlox.simpleserver.database.operations;

import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.database.FakeDatabaseOperations;
import com.phlox.simpleserver.database.TestDBEnvironment;

import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class InsertOperationTest {
    private TestDBEnvironment env;
    private FakeDatabaseOperations db;

    @BeforeEach
    public void setUp() {
        env = new TestDBEnvironment();
        db = new FakeDatabaseOperations();
    }

    @Test
    public void insertsASingleRowAndReportsItsId() throws Exception {
        JSONObject result = run(params("t", "{\"name\":\"a\"}"), TestDBEnvironment.userWith(User.DBRights.CREATE));
        assertEquals(1L, result.getLong("generated_id"));
        assertTrue(result.isNull("generated_ids"));
        assertEquals(1, db.calls.size());
        assertEquals("insert:t:{\"name\":\"a\"}", db.calls.get(0));
    }

    @Test
    public void insertsEveryRowOfABatchAndReportsAllIds() throws Exception {
        JSONObject result = run(params("t", "[{\"name\":\"a\"},{\"name\":\"b\"}]"),
                TestDBEnvironment.userWith(User.DBRights.CREATE));
        assertEquals("[1,2]", result.getJSONArray("generated_ids").toString());
        assertEquals(2, db.calls.size());
    }

    @Test
    public void refusesTheWholeBatchWhenOneRowIsForbidden() throws Exception {
        //rights are evaluated per row before anything is written, so a forbidden row can not ride
        //along with allowed ones
        env.config.setStoreUsersInDatabase(false);
        DBOperationException e = assertThrows(DBOperationException.class,
                () -> run(params("t", "[{\"name\":\"a\"},{\"name\":\"b\"}]"),
                        TestDBEnvironment.userWith(User.DBRights.READ)));
        assertEquals(DBOperationException.Kind.FORBIDDEN, e.kind);
        assertTrue(db.calls.isEmpty(), "nothing may be inserted when the batch is refused, got " + db.calls);
    }

    @Test
    public void refusesAUserWithoutTheCreateRight() {
        DBOperationException e = assertThrows(DBOperationException.class,
                () -> run(params("t", "{\"name\":\"a\"}"), TestDBEnvironment.userWith(User.DBRights.READ)));
        assertEquals(DBOperationException.Kind.FORBIDDEN, e.kind);
        assertTrue(db.calls.isEmpty());
    }

    @Test
    public void refusesWhenThereIsNoAuthenticatedUserAndAuthIsOn() {
        DBOperationException e = assertThrows(DBOperationException.class,
                () -> run(params("t", "{\"name\":\"a\"}"), null));
        assertEquals(DBOperationException.Kind.FORBIDDEN, e.kind);
    }

    @Test
    public void allowsAnythingWhenAuthenticationIsOff() throws Exception {
        env.config.setAuthMode(SHTTPSConfig.AuthMode.NONE);
        JSONObject result = run(params("t", "{\"name\":\"a\"}"), null);
        assertEquals(1L, result.getLong("generated_id"));
    }

    @Test
    public void refusesWhenTheEditingApiIsSwitchedOff() {
        env.config.setAllowDatabaseTableDataEditingApi(false);
        DBOperationException e = assertThrows(DBOperationException.class,
                () -> run(params("t", "{\"name\":\"a\"}"), TestDBEnvironment.userWith(User.DBRights.CREATE)));
        assertEquals(DBOperationException.Kind.DISABLED, e.kind);
        assertTrue(db.calls.isEmpty());
    }

    @Test
    public void rejectsMalformedValues() {
        assertBadRequest(() -> params("t", null));
        assertBadRequest(() -> params(null, "{}"));
        assertBadRequest(() -> params("t", "not json"));
        assertBadRequest(() -> params("t", "[]"));
        assertBadRequest(() -> params("t", "[{\"a\":1},42]"));
        assertBadRequest(() -> params("t", "\"a string\""));
    }

    @Test
    public void aFailedRowLetsTheExceptionOutSoTheTransactionRollsBack() {
        //returning an error instead of throwing would let the enclosing transaction commit the
        //rows that did go in
        db.failWrites = new IllegalStateException("constraint failed");
        assertThrows(IllegalStateException.class,
                () -> run(params("t", "[{\"name\":\"a\"},{\"name\":\"b\"}]"),
                        TestDBEnvironment.userWith(User.DBRights.CREATE)));
    }

    private static InsertOperation.Params params(String table, String values) throws DBOperationException {
        return InsertOperation.Params.parse(table, values);
    }

    private JSONObject run(InsertOperation.Params params, User user) throws Exception {
        return new InsertOperation(env.config, env.authManager, params).execute(db, user);
    }

    private interface ParamsBuilder {
        InsertOperation.Params build() throws DBOperationException;
    }

    private static void assertBadRequest(ParamsBuilder builder) {
        DBOperationException e = assertThrows(DBOperationException.class, builder::build);
        assertEquals(DBOperationException.Kind.BAD_REQUEST, e.kind);
    }
}
