package com.phlox.simpleserver.database;

import com.phlox.simpleserver.database.model.Table;
import com.phlox.simpleserver.database.model.TableData;
import com.phlox.simpleserver.utils.Holder;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A {@link DatabaseOperations} that records what it was asked to do and answers with whatever the
 * test set up, so that operations can be tested without a database behind them.
 * <p>
 * It returns empty results rather than nothing at all: rights evaluation runs real queries against
 * whatever it is given, and a fake that answered null there would fail before the code under test
 * had a chance to. What it can not stand in for is a rule <i>expression</i>, which is SQL written
 * by the user - those belong in a test with a real database.
 */
public class FakeDatabaseOperations implements DatabaseOperations {
    /** Every call, in order, as "method:arguments" - enough to assert on what was run. */
    public final List<String> calls = new ArrayList<>();

    public long insertGeneratedId = 1;
    public int updatedRows = 0;
    public int deletedRows = 0;
    public Table[] tables = new Table[0];
    public TableData tableDataResult = FakeTableData.empty();
    public TableData queryResult = FakeTableData.empty();
    public Database.CellDataStreamInfo cellResult = null;
    public Long totalRowCount = 0L;
    /** When set, the next write operation throws this instead of succeeding. */
    public RuntimeException failWrites = null;

    @Override
    public TableData query(String query, Object[] args, boolean possiblyWriteOperation) {
        calls.add("query:" + query);
        return queryResult;
    }

    @Override
    public void execute(String query) {
        calls.add("execute:" + query);
    }

    @Override
    public long insert(String tableName, JSONObject values) {
        calls.add("insert:" + tableName + ":" + values);
        if (failWrites != null) throw failWrites;
        return insertGeneratedId++;
    }

    @Override
    public int update(String tableName, JSONObject values, String[] whereFilters, Object[] whereArgs) {
        calls.add("update:" + tableName + ":" + values + ":" + Arrays.toString(whereFilters)
                + ":" + Arrays.toString(whereArgs));
        if (failWrites != null) throw failWrites;
        return updatedRows;
    }

    @Override
    public int delete(String tableName, String[] whereFilters, Object[] whereArgs) {
        calls.add("delete:" + tableName + ":" + Arrays.toString(whereFilters)
                + ":" + Arrays.toString(whereArgs));
        if (failWrites != null) throw failWrites;
        return deletedRows;
    }

    @Override
    public TableData getTableDataSecure(String tableName, String[] columns, Long offset, Long limit,
                                        String[] whereFilters, Object[] whereArgs, String orderBy,
                                        boolean desc, boolean includeRowId, Holder<Long> outCount) {
        calls.add("getTableDataSecure:" + tableName + ":" + Arrays.toString(columns) + ":" + offset
                + ":" + limit + ":" + Arrays.toString(whereFilters) + ":" + Arrays.toString(whereArgs)
                + ":" + orderBy + ":" + desc + ":" + includeRowId);
        if (outCount != null) {
            outCount.set(totalRowCount);
        }
        return tableDataResult;
    }

    @Override
    public Database.CellDataStreamInfo getSingleCellDataStream(String table, String column,
                                                               List<String> filters, List<Object> filtersArgs) {
        calls.add("getSingleCellDataStream:" + table + ":" + column + ":" + filters + ":" + filtersArgs);
        return cellResult;
    }

    @Override
    public Table[] getTables() {
        calls.add("getTables");
        return tables;
    }
}
