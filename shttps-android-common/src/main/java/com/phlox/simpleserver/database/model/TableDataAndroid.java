package com.phlox.simpleserver.database.model;

import android.database.Cursor;
import android.util.Base64;

import com.phlox.server.utils.SHTTPSLoggerProxy;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class TableDataAndroid implements TableData {
    private Cursor cursor;
    private final SHTTPSLoggerProxy.Logger logger = SHTTPSLoggerProxy.getLogger(getClass());

    public TableDataAndroid(Cursor cursor) {
        this.cursor = cursor;
    }

    @Override
    public void close() {
        cursor.close();
    }

    @Override
    public boolean next() {
        return cursor.moveToNext();
    }

    @Override
    public boolean skip(int count) {
        return cursor.move(count);
    }

    @Override
    public int getColumnIndex(String columnName) {
        return cursor.getColumnIndex(columnName);
    }

    @Override
    public long count() {
        return cursor.getCount();
    }

    @Override
    public String getString(int columnIndex) {
        return cursor.getString(columnIndex);
    }

    @Override
    public int getInt(int columnIndex) {
        return cursor.getInt(columnIndex);
    }

    @Override
    public long getLong(int columnIndex) {
        return cursor.getLong(columnIndex);
    }

    @Override
    public double getDouble(int columnIndex) {
        return cursor.getDouble(columnIndex);
    }

    @Override
    public byte[] getBlob(int columnIndex) {
        return cursor.getBlob(columnIndex);
    }

    @Override
    public boolean getBoolean(int columnIndex) {
        return cursor.getInt(columnIndex) != 0;
    }

    @Override
    public Integer getInt(int columnIndex, Integer defaultValue) {
        if (cursor.isNull(columnIndex)) {
            return defaultValue;
        }
        return cursor.getInt(columnIndex);
    }

    @Override
    public Long getLong(int columnIndex, Long defaultValue) {
        if (cursor.isNull(columnIndex)) {
            return defaultValue;
        }
        return cursor.getLong(columnIndex);
    }

    @Override
    public Double getDouble(int columnIndex, Double defaultValue) {
        if (cursor.isNull(columnIndex)) {
            return defaultValue;
        }
        return cursor.getDouble(columnIndex);
    }

    @Override
    public Boolean getBoolean(int columnIndex, Boolean defaultValue) {
        if (cursor.isNull(columnIndex)) {
            return defaultValue;
        }
        return cursor.getInt(columnIndex) != 0;
    }

    @Override
    public String[] getColumnNames() {
        return cursor.getColumnNames();
    }

    @Override
    public JSONArray toJson(boolean rowsAsObjects) {
        JSONArray json = new JSONArray();
        if (cursor.moveToFirst()) {
            if (rowsAsObjects) {
                do {
                    json.put(currentRowToJsonObject());
                } while (cursor.moveToNext());
            } else {
                do {
                    json.put(currentRowToJson());
                } while (cursor.moveToNext());
            }
        }
        return json;
    }

    @Override
    public JSONArray currentRowToJson() {
        JSONArray row = new JSONArray();
        int columnCount = cursor.getColumnCount();
        for (int i = 0; i < columnCount; i++) {
            row.put(getColumnValueAsJson(i));
        }
        return row;
    }

    @Override
    public JSONObject currentRowToJsonObject() {
        JSONObject row = new JSONObject();
        int columnCount = cursor.getColumnCount();
        for (int i = 0; i < columnCount; i++) {
            String columnName = cursor.getColumnName(i);
            try {
                row.put(columnName, getColumnValueAsJson(i));
            } catch (JSONException e) {
                logger.e("Error adding column " + columnName + " to JSON object", e);
            }
        }
        return row;
    }

    /**
     * Helper method to convert a column value to a JSON-compatible value
     * @param columnIndex The column index
     * @return The column value as a JSON-compatible object
     */
    private Object getColumnValueAsJson(int columnIndex) {
        if (cursor.isNull(columnIndex)) {
            return JSONObject.NULL;
        }
        
        switch (cursor.getType(columnIndex)) {
            case Cursor.FIELD_TYPE_INTEGER:
                return cursor.getLong(columnIndex);
            case Cursor.FIELD_TYPE_FLOAT:
                try {
                    return cursor.getDouble(columnIndex);
                } catch (Exception e) {
                    logger.e("Error converting column " + cursor.getColumnName(columnIndex) + " to double", e);
                    return cursor.getString(columnIndex);
                }
            case Cursor.FIELD_TYPE_BLOB:
                JSONObject blobJson = new JSONObject();
                try {
                    blobJson.put("type", "blob");
                } catch (JSONException ignored) {}
                return blobJson;
            case Cursor.FIELD_TYPE_NULL:
                return JSONObject.NULL;
            default:
                return cursor.getString(columnIndex);
        }
    }
}
