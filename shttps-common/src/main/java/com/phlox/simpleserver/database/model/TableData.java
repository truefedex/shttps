package com.phlox.simpleserver.database.model;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Hides the platform-specific implementation of the database table data
 * structure (Cursor for Android, ResultSet for Java SE).
 */
public interface TableData extends AutoCloseable {
    boolean next();
    boolean skip(int count);
    String[] getColumnNames();
    @Override void close();
    int getColumnIndex(String columnName);
    String getString(int columnIndex) throws Exception;
    int getInt(int columnIndex) throws Exception;
    long getLong(int columnIndex) throws Exception;
    double getDouble(int columnIndex) throws Exception;
    byte[] getBlob(int columnIndex) throws Exception;
    boolean getBoolean(int columnIndex) throws Exception;

    Integer getInt(int columnIndex, Integer defaultValue);
    Long getLong(int columnIndex, Long defaultValue);
    Double getDouble(int columnIndex, Double defaultValue);
    Boolean getBoolean(int columnIndex, Boolean defaultValue);
    boolean isNull(int col);

    JSONArray toJson(boolean rowsAsObjects);
    JSONArray currentRowToJson();
    JSONObject currentRowToJsonObject();
}
