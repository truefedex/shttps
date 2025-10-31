package com.phlox.server.database.model;

import com.phlox.server.database.DatabaseImpl;
import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.simpleserver.database.model.TableData;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class TableDataImpl implements TableData {
    private DatabaseImpl.ManagedConnection connection;
    private Statement statement;
    private ResultSet resultSet;
    private final SHTTPSLoggerProxy.Logger logger = SHTTPSLoggerProxy.getLogger(getClass());

    public TableDataImpl(DatabaseImpl.ManagedConnection connection, Statement statement, ResultSet resultSet) {
        this.connection = connection;
        this.statement = statement;
        this.resultSet = resultSet;
    }

    @Override
    public void close() {
        try {
            resultSet.close();
        } catch (SQLException e) {
            logger.e("Error closing result set", e);
        }
        try {
            statement.close();
        } catch (SQLException e) {
            logger.e("Error closing statement", e);
        }
        try {
            connection.close();
        } catch (SQLException e) {
            logger.e("Error closing connection", e);
        }
    }

    @Override
    public String[] getColumnNames() {
        try {
            int columnCount = resultSet.getMetaData().getColumnCount();
            String[] columnNames = new String[columnCount];
            for (int i = 1; i <= columnCount; i++) {
                columnNames[i - 1] = resultSet.getMetaData().getColumnName(i);
            }
            return columnNames;
        } catch (SQLException e) {
            logger.e("Error getting column names", e);
            return new String[0];
        }
    }

    @Override
    public boolean next() {
        try {
            return resultSet.next();
        } catch (SQLException e) {
            logger.e("Error moving to next row", e);
            return false;
        }
    }

    @Override
    public boolean skip(int count) {
        try {
            return resultSet.relative(count);
        } catch (SQLException e) {
            logger.e("Error skipping rows", e);
            return false;
        }
    }

    @Override
    public int getColumnIndex(String columnName) {
        try {
            return resultSet.findColumn(columnName) - 1;
        } catch (SQLException e) {
            logger.e("Error finding column index", e);
            return -1;
        }
    }

    @Override
    public String getString(int columnIndex) throws Exception {
        return resultSet.getString(++columnIndex);
    }

    @Override
    public int getInt(int columnIndex) throws Exception {
        return resultSet.getInt(++columnIndex);
    }

    @Override
    public long getLong(int columnIndex) throws Exception {
        return resultSet.getLong(++columnIndex);
    }

    @Override
    public double getDouble(int columnIndex) throws Exception {
        return resultSet.getDouble(++columnIndex);
    }

    @Override
    public byte[] getBlob(int columnIndex) throws Exception {
        return resultSet.getBytes(++columnIndex);
    }

    @Override
    public boolean getBoolean(int columnIndex) throws Exception {
        return resultSet.getBoolean(++columnIndex);
    }

    @Override
    public Integer getInt(int columnIndex, Integer defaultValue) {
        try {
            int result = resultSet.getInt(++columnIndex);
            return resultSet.wasNull() ? defaultValue : result;
        } catch (SQLException e) {
            logger.e("Error getting int value", e);
            return defaultValue;
        }
    }

    @Override
    public Long getLong(int columnIndex, Long defaultValue) {
        try {
            long result = resultSet.getLong(++columnIndex);
            return resultSet.wasNull() ? defaultValue : result;
        } catch (SQLException e) {
            logger.e("Error getting long value", e);
            return defaultValue;
        }
    }

    @Override
    public Double getDouble(int columnIndex, Double defaultValue) {
        try {
            double result = resultSet.getDouble(++columnIndex);
            return resultSet.wasNull() ? defaultValue : result;
        } catch (SQLException e) {
            logger.e("Error getting double value", e);
            return defaultValue;
        }
    }

    @Override
    public Boolean getBoolean(int columnIndex, Boolean defaultValue) {
        try {
            boolean result = resultSet.getBoolean(++columnIndex);
            return resultSet.wasNull() ? defaultValue : result;
        } catch (SQLException e) {
            logger.e("Error getting boolean value", e);
            return defaultValue;
        }
    }

    @Override
    public boolean isNull(int col) {
        try {
            resultSet.getObject(++col);
            return resultSet.wasNull();
        } catch (SQLException e) {
            logger.e("Error checking for null", e);
            return true;
        }
    }

    @Override
    public JSONArray toJson(boolean rowsAsObjects) {
        JSONArray json = new JSONArray();
        try {
            if (rowsAsObjects) {
                while (resultSet.next()) {
                    json.put(currentRowToJsonObject());
                }
            } else {
                while (resultSet.next()) {
                    json.put(currentRowToJson());
                }
            }
        } catch (SQLException e) {
            logger.e("Error converting result set to JSON", e);
        }
        return json;
    }

    @Override
    public JSONArray currentRowToJson() {
        JSONArray row = new JSONArray();
        try {
            int columnCount = resultSet.getMetaData().getColumnCount();
            for (int i = 1; i <= columnCount; i++) {
                row.put(getColumnValueAsJson(i));
            }
        } catch (SQLException e) {
            logger.e("Error converting result set to JSON", e);
        }
        return row;
    }

    @Override
    public JSONObject currentRowToJsonObject() {
        JSONObject row = new JSONObject();
        try {
            int columnCount = resultSet.getMetaData().getColumnCount();
            for (int i = 1; i <= columnCount; i++) {
                String columnName = resultSet.getMetaData().getColumnName(i);
                row.put(columnName, getColumnValueAsJson(i));
            }
        } catch (SQLException e) {
            logger.e("Error converting result set to JSON", e);
        }
        return row;
    }

    /**
     * Helper method to convert a column value to a JSON-compatible value
     * @param columnIndex The 1-based column index
     * @return The column value as a JSON-compatible object
     */
    private Object getColumnValueAsJson(int columnIndex) {
        try {
            switch (resultSet.getMetaData().getColumnType(columnIndex)) {
                case java.sql.Types.BIGINT: case java.sql.Types.BOOLEAN:
                case java.sql.Types.TINYINT: case java.sql.Types.SMALLINT:
                case java.sql.Types.INTEGER: case java.sql.Types.NUMERIC:
                    return resultSet.getLong(columnIndex);

                case java.sql.Types.BLOB:
                    //noinspection EmptyTryBlock
                    try (InputStream is = resultSet.getBinaryStream(columnIndex)) {
                        //just to test for null
                    }
                    if (resultSet.wasNull()) {
                        return JSONObject.NULL;
                    } else {
                        JSONObject blobJson = new JSONObject();
                        blobJson.put("type", "blob");
                        return blobJson;
                    }

                case java.sql.Types.DOUBLE: case java.sql.Types.FLOAT: case java.sql.Types.REAL:
                    return resultSet.getDouble(columnIndex);

                default:
                    String strValue = resultSet.getString(columnIndex);
                    if (resultSet.wasNull()) {
                        return JSONObject.NULL;
                    } else {
                        return strValue;
                    }
            }
        } catch (Exception e) {
            logger.e("Error converting column to JSON", e);
            return JSONObject.NULL;
        }
    }
}
