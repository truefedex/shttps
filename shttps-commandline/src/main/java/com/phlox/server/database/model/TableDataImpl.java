package com.phlox.server.database.model;

import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.simpleserver.database.model.TableData;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;

public class TableDataImpl implements TableData {
    private Connection connection;
    private Statement statement;
    private ResultSet resultSet;
    private final SHTTPSLoggerProxy.Logger logger = SHTTPSLoggerProxy.getLogger(getClass());

    public TableDataImpl(Connection connection, Statement statement, ResultSet resultSet) {
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
            return resultSet.findColumn(columnName);
        } catch (SQLException e) {
            logger.e("Error finding column index", e);
            return -1;
        }
    }

    @Override
    public long count() {
        try {
            return resultSet.getFetchSize();
        } catch (SQLException e) {
            logger.e("Error getting row count", e);
            return 0;
        }
    }

    @Override
    public String getString(int columnIndex) {
        try {
            return resultSet.getString(columnIndex);
        } catch (SQLException e) {
            logger.e("Error getting string value", e);
            return null;
        }
    }

    @Override
    public int getInt(int columnIndex) {
        try {
            return resultSet.getInt(columnIndex);
        } catch (SQLException e) {
            logger.e("Error getting int value", e);
            return 0;
        }
    }

    @Override
    public long getLong(int columnIndex) {
        try {
            return resultSet.getLong(columnIndex);
        } catch (SQLException e) {
            logger.e("Error getting long value", e);
            return 0;
        }
    }

    @Override
    public double getDouble(int columnIndex) {
        try {
            return resultSet.getDouble(columnIndex);
        } catch (SQLException e) {
            logger.e("Error getting double value", e);
            return 0.0;
        }
    }

    @Override
    public byte[] getBlob(int columnIndex) {
        try {
            return resultSet.getBytes(columnIndex);
        } catch (SQLException e) {
            logger.e("Error getting blob value", e);
            return new byte[0];
        }
    }

    @Override
    public boolean getBoolean(int columnIndex) {
        try {
            return resultSet.getBoolean(columnIndex);
        } catch (SQLException e) {
            logger.e("Error getting boolean value", e);
            return false;
        }
    }

    @Override
    public Integer getInt(int columnIndex, Integer defaultValue) {
        try {
            int result = resultSet.getInt(columnIndex);
            return resultSet.wasNull() ? defaultValue : result;
        } catch (SQLException e) {
            logger.e("Error getting int value", e);
            return defaultValue;
        }
    }

    @Override
    public Long getLong(int columnIndex, Long defaultValue) {
        try {
            long result = resultSet.getLong(columnIndex);
            return resultSet.wasNull() ? defaultValue : result;
        } catch (SQLException e) {
            logger.e("Error getting long value", e);
            return defaultValue;
        }
    }

    @Override
    public Double getDouble(int columnIndex, Double defaultValue) {
        try {
            double result = resultSet.getDouble(columnIndex);
            return resultSet.wasNull() ? defaultValue : result;
        } catch (SQLException e) {
            logger.e("Error getting double value", e);
            return defaultValue;
        }
    }

    @Override
    public Boolean getBoolean(int columnIndex, Boolean defaultValue) {
        try {
            boolean result = resultSet.getBoolean(columnIndex);
            return resultSet.wasNull() ? defaultValue : result;
        } catch (SQLException e) {
            logger.e("Error getting boolean value", e);
            return defaultValue;
        }
    }

    @Override
    public JSONArray toJson() {
        JSONArray json = new JSONArray();
        try {
            while (resultSet.next()) {
                json.put(currentRowToJson());
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
                try {
                    switch (resultSet.getMetaData().getColumnType(i)) {
                        case java.sql.Types.BIGINT: case java.sql.Types.BOOLEAN:
                            case java.sql.Types.TINYINT: case java.sql.Types.SMALLINT:
                            case java.sql.Types.INTEGER: case java.sql.Types.NUMERIC:
                            row.put(resultSet.getLong(i));
                            break;

                        case java.sql.Types.BLOB:
                            //noinspection EmptyTryBlock
                            try (InputStream is = resultSet.getBinaryStream(i)) {
                                //just to test for null
                            }
                            if (resultSet.wasNull()) {
                                row.put((Object) null);
                            } else {
                                JSONObject blobJson = new JSONObject();
                                blobJson.put("type", "blob");
                                row.put(blobJson);
                            }
                            break;
                        case java.sql.Types.DOUBLE: case java.sql.Types.FLOAT: case java.sql.Types.REAL:
                            row.put(resultSet.getDouble(i));
                            break;

                        default:
                            String strValue = resultSet.getString(i);
                            if (resultSet.wasNull()) {
                                row.put((Object) null);
                            } else {
                                row.put(strValue);
                            }
                            break;
                    }
                } catch (Exception e) {
                    logger.e("Error converting column to JSON", e);
                    row.put((Object) null);
                }
            }
        } catch (SQLException e) {
            logger.e("Error converting result set to JSON", e);
        }
        return row;
    }
}
