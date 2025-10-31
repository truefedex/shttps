package com.phlox.simpleserver.database;

import com.phlox.simpleserver.database.model.Table;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

public interface Database extends AutoCloseable, DatabaseOperations {
    String getPath();
    Map<String, Object> getStatus() throws IOException;
    @Override
    void close() throws Exception;

    Table[] getTables() throws Exception;
    <T> T runTransaction(DatabaseTransactionScope<T> tx) throws Exception;

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
