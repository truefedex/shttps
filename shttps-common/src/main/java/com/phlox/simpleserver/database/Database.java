package com.phlox.simpleserver.database;

import com.phlox.simpleserver.database.model.Table;
import com.phlox.simpleserver.database.model.TableData;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

public interface Database extends AutoCloseable {
    String getPath();
    Map<String, Object> getStatus() throws IOException;
    @Override
    void close() throws Exception;

    //interfaces to manipulate schema
    Table[] getTables() throws Exception;

    //interfaces to manipulate data
    TableData execute(String query) throws Exception;
    long insert(String tableName, JSONObject values) throws Exception;
    int update(String tableName, JSONObject values, String[] whereFilters, Object[] whereArgs) throws Exception;
    int delete(String tableName, String[] whereFilters, Object[] whereArgs) throws Exception;
    TableData getTableDataSecure(String tableName, String[] columns, Long offset, Long limit, String[] whereFilters,
                                 Object[] whereArgs, String orderBy, boolean desc, boolean includeRowId) throws Exception;

    CellDataStreamInfo getSingleCellDataStream(String table, String column, List<String> filters, List<Object> filtersArgs) throws Exception;

    /**
     * This datatype is used for retrieving large string or binary data from a cell in a table.
     */
    class CellDataStreamInfo {
        public InputStream inputStream;
        public String type;
        public String mimeType;
        public long length;
    }
}
