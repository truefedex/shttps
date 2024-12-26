package com.phlox.simpleserver.database;

import com.phlox.simpleserver.database.model.Table;
import com.phlox.simpleserver.database.model.TableData;

import java.io.IOException;
import java.util.Map;

public interface Database extends AutoCloseable {
    String getPath();
    Map<String, Object> getStatus() throws IOException;
    @Override
    void close() throws Exception;

    //interfaces to manipulate schema
    Table[] getTables() throws Exception;

    //interfaces to manipulate data
    TableData query(String query) throws Exception;
    ExecuteResult execute(String query) throws Exception;
    long insert(String tableName, Map<String, Object> values) throws Exception;
    int update(String tableName, Map<String, Object> values, String[] whereFilters, Object[] whereArgs) throws Exception;
    int delete(String tableName, String[] whereFilters, Object[] whereArgs) throws Exception;
    TableData getTableDataSecure(String tableName, String[] columns, Long offset, Long limit, String[] whereFilters,
                                 Object[] whereArgs, String orderBy, boolean desc, boolean includeRowId) throws Exception;

    public static class ExecuteResult {
        public final int updatedRows;
        public final long generatedId;

        public ExecuteResult(int updatedRows, long generatedId) {
            this.updatedRows = updatedRows;
            this.generatedId = generatedId;
        }
    }
}
