package com.phlox.simpleserver.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.DatabaseOperations;
import com.phlox.simpleserver.database.DatabaseTransactionScope;
import com.phlox.simpleserver.database.FakeDatabaseOperations;
import com.phlox.simpleserver.database.FakeTableData;
import com.phlox.simpleserver.database.TransactionAbortHandle;
import com.phlox.simpleserver.database.model.Table;
import com.phlox.simpleserver.database.model.TableData;
import com.phlox.simpleserver.utils.Holder;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

public class UserRightsEvaluatorTest {

    @Test
    public void normalizeDropsEmptyAndDotSegments() {
        assertEquals("/private/file", UserRightsEvaluator.normalizeFsPath("/./private/file"));
        assertEquals("/private/file", UserRightsEvaluator.normalizeFsPath("/private/./file"));
        assertEquals("/private/file", UserRightsEvaluator.normalizeFsPath("//private//file"));
        assertEquals("/", UserRightsEvaluator.normalizeFsPath("/"));
        assertEquals("/", UserRightsEvaluator.normalizeFsPath(null));
        //".." is not rewritten into some other file; the lookup rejects it on its own
        assertEquals("/../private", UserRightsEvaluator.normalizeFsPath("/../private"));
    }

    @Test
    public void subjectMatchRequiresASegmentBoundary() {
        assertTrue(UserRightsEvaluator.fsSubjectCovers("/secret", "/secret"));
        assertTrue(UserRightsEvaluator.fsSubjectCovers("/secret", "/secret/file"));
        assertTrue(UserRightsEvaluator.fsSubjectCovers("/secret/", "/secret/file"));
        assertFalse(UserRightsEvaluator.fsSubjectCovers("/secret", "/secrets"));
        assertFalse(UserRightsEvaluator.fsSubjectCovers("/secret", "/secret-backup"));
        //"_" and "%" used to be LIKE wildcards
        assertFalse(UserRightsEvaluator.fsSubjectCovers("/sec_et", "/secret"));
        assertFalse(UserRightsEvaluator.fsSubjectCovers("/sec%", "/secret"));
        assertTrue(UserRightsEvaluator.fsSubjectCovers("/private", "/./private/file"));
        assertTrue(UserRightsEvaluator.fsSubjectCovers("/", "/anything"));
    }

    @Test
    public void longestCoveringRuleWinsAndAnAllowIntegerIsHonored() throws Exception {
        FakeDatabaseOperations ops = new FakeDatabaseOperations();
        ops.queryResult = FakeTableData.of(
                new String[]{"role_name", "subject", "operation", "allow", "expression"},
                new Object[]{"editors", "/s", "DOWNLOAD", 0L, null},
                new Object[]{"editors", "/secret", "DOWNLOAD", 1L, null},
                new Object[]{"editors", "/secrets", "DOWNLOAD", 0L, null});
        User user = userWith(EnumSet.noneOf(User.FileSystemRights.class));
        user.role = "editors";

        UserRightsEvaluator evaluator = new UserRightsEvaluator(new Holder<>(database(ops)));
        assertTrue(evaluator.checkIsFileOperationAllowed(user, true, "/secret/file", "DOWNLOAD", null,
                User.FileSystemRights.READ));
        assertFalse(evaluator.checkIsFileOperationAllowed(user, true, "/secrets/file", "DOWNLOAD", null,
                User.FileSystemRights.READ));
    }

    @Test
    public void dotSegmentsAreMatchedAgainstThePathTheFileLookupResolves() throws Exception {
        FakeDatabaseOperations ops = new FakeDatabaseOperations();
        ops.queryResult = FakeTableData.of(
                new String[]{"role_name", "subject", "operation", "allow", "expression"},
                new Object[]{"editors", "/private", "DOWNLOAD", 0L, null});
        User user = userWith(EnumSet.of(User.FileSystemRights.READ));
        user.role = "editors";

        UserRightsEvaluator evaluator = new UserRightsEvaluator(new Holder<>(database(ops)));
        assertFalse(evaluator.checkIsFileOperationAllowed(user, true, "/./private/file", "DOWNLOAD", null,
                User.FileSystemRights.READ));
    }

    @Test
    public void aFailedRuleLookupDeniesInsteadOfUsingTheRoleBits() throws Exception {
        FakeDatabaseOperations ops = new FakeDatabaseOperations() {
            @Override
            public TableData query(String query, Object[] args, boolean possiblyWriteOperation) {
                throw new RuntimeException("rules unavailable");
            }
        };
        User user = userWith(EnumSet.of(User.FileSystemRights.READ));
        user.role = "editors";
        UserRightsEvaluator evaluator = new UserRightsEvaluator(new Holder<>(database(ops)));
        assertFalse(evaluator.checkIsFileOperationAllowed(user, true, "/file", "DOWNLOAD", null,
                User.FileSystemRights.READ));
    }

    private static User userWith(EnumSet<User.FileSystemRights> rights) {
        return new User("alice", "hash", null, rights, EnumSet.noneOf(User.DBRights.class), null,
                0L, null, null, EnumSet.noneOf(User.SystemRights.class), 0L);
    }

    /** Runs the scope inline on {@code ops}. Nothing else is a database. */
    private static Database database(DatabaseOperations ops) {
        return new Database() {
            @Override public String getPath() { return ":memory:"; }
            @Override public Map<String, Object> getStatus() { return Map.of(); }
            @Override public void close() {}
            @Override public <T> T runTransaction(DatabaseTransactionScope<T> tx) throws Exception {
                return tx.execute(ops);
            }
            @Override public <T> T runTransaction(DatabaseTransactionScope<T> tx, TransactionAbortHandle abortHandle) throws Exception {
                return tx.execute(ops);
            }
            @Override public Table[] getTables() throws Exception { return ops.getTables(); }
            @Override public TableData query(String query, Object[] args, boolean possiblyWriteOperation) throws Exception {
                return ops.query(query, args, possiblyWriteOperation);
            }
            @Override public void execute(String query) throws Exception { ops.execute(query); }
            @Override public long insert(String tableName, JSONObject values) throws Exception {
                return ops.insert(tableName, values);
            }
            @Override public int update(String tableName, JSONObject values, String[] whereFilters, Object[] whereArgs) throws Exception {
                return ops.update(tableName, values, whereFilters, whereArgs);
            }
            @Override public int delete(String tableName, String[] whereFilters, Object[] whereArgs) throws Exception {
                return ops.delete(tableName, whereFilters, whereArgs);
            }
            @Override public TableData getTableDataSecure(String tableName, String[] columns, Long offset, Long limit,
                                                           String[] whereFilters, Object[] whereArgs, String orderBy,
                                                           boolean desc, boolean includeRowId, Holder<Long> outCount) throws Exception {
                return ops.getTableDataSecure(tableName, columns, offset, limit, whereFilters, whereArgs,
                        orderBy, desc, includeRowId, outCount);
            }
            @Override public CellDataStreamInfo getSingleCellDataStream(String table, String column, List<String> filters, List<Object> filtersArgs) throws Exception {
                return ops.getSingleCellDataStream(table, column, filters, filtersArgs);
            }
        };
    }
}
