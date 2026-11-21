package com.phlox.simpleserver.database;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.phlox.simpleserver.database.model.Table;
import com.phlox.simpleserver.database.model.TableData;
import com.phlox.simpleserver.utils.Holder;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * A brand-new database whose users live in the database is created at the current schema by
 * {@code toggleUsersInDB}. Recording that as version 1 makes the following ALTER steps run against
 * columns that already exist.
 */
public class DatabaseMigratorTest {

    @Test
    public void aFreshUsersDatabaseIsStampedCurrentAndNotAltered() throws Exception {
        RecordingOperations ops = new RecordingOperations();
        ops.queryResult = FakeTableData.of(new String[]{"count"}, new Object[]{0});

        DatabaseMigrator.runMigrations(database(ops), true);

        assertTrue(ops.calls.stream().anyMatch(call -> call.contains("\"version\":6") || call.contains("\"version\": 6")),
                ops.calls.toString());
        assertFalse(ops.calls.stream().anyMatch(call -> call.contains("ALTER TABLE")),
                ops.calls.toString());
    }

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

    private static final class RecordingOperations extends FakeDatabaseOperations {
    }
}
