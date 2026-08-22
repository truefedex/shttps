package com.phlox.simpleserver.database;

import com.phlox.simpleserver.database.model.Table;
import com.phlox.simpleserver.database.model.TableData;
import com.phlox.simpleserver.utils.Holder;

import org.json.JSONObject;

import java.util.List;

public interface DatabaseOperations {
    /**
     * The tables of this database with their columns, indexes and row counts.
     * <p>
     * Declared here rather than on {@link Database} so that it can be read from inside a
     * transaction, together with whatever else that transaction looks at. Reading the schema
     * through the outer {@link Database} while a permission check ran in a transaction would answer
     * from two different points in time.
     */
    Table[] getTables() throws Exception;

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
