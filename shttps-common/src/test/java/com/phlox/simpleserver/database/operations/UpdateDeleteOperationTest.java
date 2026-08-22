package com.phlox.simpleserver.database.operations;

import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.database.FakeDatabaseOperations;
import com.phlox.simpleserver.database.TestDBEnvironment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UpdateDeleteOperationTest {
    private static final String FILTERS = "{\"clauses\":[\"id=\"],\"args\":[7]}";

    private TestDBEnvironment env;
    private FakeDatabaseOperations db;

    @BeforeEach
    public void setUp() {
        env = new TestDBEnvironment();
        db = new FakeDatabaseOperations();
    }

    @Test
    public void updatePassesTableValuesAndFiltersThrough() throws Exception {
        db.updatedRows = 3;
        int updated = update("t", "{\"name\":\"a\"}", FILTERS, TestDBEnvironment.userWith(User.DBRights.UPDATE));
        assertEquals(3, updated);
        assertEquals("update:t:{\"name\":\"a\"}:[id=]:[7]", db.calls.get(0));
    }

    @Test
    public void deletePassesTableAndFiltersThrough() throws Exception {
        db.deletedRows = 2;
        int deleted = delete("t", FILTERS, TestDBEnvironment.userWith(User.DBRights.DELETE));
        assertEquals(2, deleted);
        assertEquals("delete:t:[id=]:[7]", db.calls.get(0));
    }

    @Test
    public void aDeleteWithoutFiltersTouchesEveryRow() throws Exception {
        //no filters is a legitimate "delete everything"; it must not silently become a no-op
        delete("t", null, TestDBEnvironment.userWith(User.DBRights.DELETE));
        assertEquals("delete:t:[]:[]", db.calls.get(0));
    }

    @Test
    public void updateNeedsTheUpdateRight() {
        assertForbidden(() -> update("t", "{\"name\":\"a\"}", FILTERS,
                TestDBEnvironment.userWith(User.DBRights.CREATE, User.DBRights.DELETE)));
        assertTrue(db.calls.isEmpty());
    }

    @Test
    public void deleteNeedsTheDeleteRight() {
        assertForbidden(() -> delete("t", FILTERS,
                TestDBEnvironment.userWith(User.DBRights.CREATE, User.DBRights.UPDATE)));
        assertTrue(db.calls.isEmpty());
    }

    @Test
    public void bothRefuseWhenTheEditingApiIsSwitchedOff() {
        env.config.setAllowDatabaseTableDataEditingApi(false);
        assertKind(DBOperationException.Kind.DISABLED,
                () -> update("t", "{\"name\":\"a\"}", FILTERS, TestDBEnvironment.userWith(User.DBRights.UPDATE)));
        assertKind(DBOperationException.Kind.DISABLED,
                () -> delete("t", FILTERS, TestDBEnvironment.userWith(User.DBRights.DELETE)));
        assertTrue(db.calls.isEmpty());
    }

    @Test
    public void bothRefuseWhenThereIsNoAuthenticatedUserAndAuthIsOn() {
        assertForbidden(() -> update("t", "{\"name\":\"a\"}", FILTERS, null));
        assertForbidden(() -> delete("t", FILTERS, null));
    }

    @Test
    public void bothAllowAnythingWhenAuthenticationIsOff() throws Exception {
        env.config.setAuthMode(SHTTPSConfig.AuthMode.NONE);
        update("t", "{\"name\":\"a\"}", FILTERS, null);
        delete("t", FILTERS, null);
        assertEquals(2, db.calls.size());
    }

    @Test
    public void bothRejectAMissingTable() {
        assertKind(DBOperationException.Kind.BAD_REQUEST,
                () -> update(null, "{\"name\":\"a\"}", FILTERS, TestDBEnvironment.userWith(User.DBRights.UPDATE)));
        assertKind(DBOperationException.Kind.BAD_REQUEST,
                () -> delete(null, FILTERS, TestDBEnvironment.userWith(User.DBRights.DELETE)));
    }

    @Test
    public void updateRejectsMalformedValues() {
        assertKind(DBOperationException.Kind.BAD_REQUEST,
                () -> update("t", "not json", FILTERS, TestDBEnvironment.userWith(User.DBRights.UPDATE)));
        assertKind(DBOperationException.Kind.BAD_REQUEST,
                () -> update("t", null, FILTERS, TestDBEnvironment.userWith(User.DBRights.UPDATE)));
    }

    @Test
    public void bothRejectMalformedFilters() {
        assertKind(DBOperationException.Kind.BAD_REQUEST,
                () -> update("t", "{}", "{\"clauses\":[]}", TestDBEnvironment.userWith(User.DBRights.UPDATE)));
        assertKind(DBOperationException.Kind.BAD_REQUEST,
                () -> delete("t", "nonsense", TestDBEnvironment.userWith(User.DBRights.DELETE)));
    }

    @Test
    public void aFailedWriteLetsTheExceptionOutSoTheTransactionRollsBack() {
        db.failWrites = new IllegalStateException("constraint failed");
        assertThrows(IllegalStateException.class,
                () -> update("t", "{\"name\":\"a\"}", FILTERS, TestDBEnvironment.userWith(User.DBRights.UPDATE)));
        assertThrows(IllegalStateException.class,
                () -> delete("t", FILTERS, TestDBEnvironment.userWith(User.DBRights.DELETE)));
    }

    private int update(String table, String values, String filters, User user) throws Exception {
        return new UpdateOperation(env.config, env.authManager,
                UpdateOperation.Params.parse(table, values, filters)).execute(db, user);
    }

    private int delete(String table, String filters, User user) throws Exception {
        return new DeleteOperation(env.config, env.authManager,
                DeleteOperation.Params.parse(table, filters)).execute(db, user);
    }

    private interface Action {
        void run() throws Exception;
    }

    private static void assertForbidden(Action action) {
        assertKind(DBOperationException.Kind.FORBIDDEN, action);
    }

    private static void assertKind(DBOperationException.Kind kind, Action action) {
        DBOperationException e = assertThrows(DBOperationException.class, action::run);
        assertEquals(kind, e.kind);
    }
}
