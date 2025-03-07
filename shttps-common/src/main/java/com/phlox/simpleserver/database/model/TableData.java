package com.phlox.simpleserver.database.model;

import org.json.JSONArray;

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
    long count();
    String getString(int columnIndex);
    int getInt(int columnIndex);
    long getLong(int columnIndex);
    double getDouble(int columnIndex);
    byte[] getBlob(int columnIndex);
    boolean getBoolean(int columnIndex);

    Integer getInt(int columnIndex, Integer defaultValue);
    Long getLong(int columnIndex, Long defaultValue);
    Double getDouble(int columnIndex, Double defaultValue);
    Boolean getBoolean(int columnIndex, Boolean defaultValue);

    JSONArray toJson();
    JSONArray currentRowToJson();
}
