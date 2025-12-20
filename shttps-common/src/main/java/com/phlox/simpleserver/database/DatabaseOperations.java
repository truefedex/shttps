package com.phlox.simpleserver.database;

import com.phlox.simpleserver.database.model.TableData;
import com.phlox.simpleserver.utils.Holder;

import org.json.JSONObject;

import java.util.List;

public interface DatabaseOperations {
    default TableData query(String query) throws Exception {
        return query(query, null, true);
    }
    TableData query(String query, Object[] args, boolean possiblyWriteOperation) throws Exception;
    void execute(String query) throws Exception;
    long insert(String tableName, JSONObject values) throws Exception;
    int update(String tableName, JSONObject values, String[] whereFilters, Object[] whereArgs) throws Exception;
    int delete(String tableName, String[] whereFilters, Object[] whereArgs) throws Exception;
    TableData getTableDataSecure(String tableName, String[] columns, Long offset, Long limit, String[] whereFilters,
                                 Object[] whereArgs, String orderBy, boolean desc, boolean includeRowId, Holder<Long> outCount) throws Exception;
    default TableData getTableDataSecure(String tableName) throws Exception {
        return getTableDataSecure(tableName, null, null, null,
                null, null, null, false, false, null);
    }

    Database.CellDataStreamInfo getSingleCellDataStream(String table, String column, List<String> filters, List<Object> filtersArgs) throws Exception;

}
