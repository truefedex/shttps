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
    public JSONArray toJson() {
        JSONArray json = new JSONArray();
        if (cursor.moveToFirst()) {
            do {
                int columnCount = cursor.getColumnCount();
                JSONArray row = new JSONArray();
                for (int i = 0; i < columnCount; i++) {
                    if (cursor.isNull(i)) {
                        row.put(null);
                        continue;
                    }
                    switch (cursor.getType(i)) {
                        case Cursor.FIELD_TYPE_INTEGER:
                            row.put(cursor.getLong(i));
                            break;
                        case Cursor.FIELD_TYPE_FLOAT:
                            try {
                                row.put(cursor.getDouble(i));
                            } catch (JSONException e) {
                                logger.e("Error converting column " + cursor.getColumnName(i) + " to double", e);
                                row.put(cursor.getString(i));
                            }
                            break;
                        case Cursor.FIELD_TYPE_BLOB:
                            JSONObject blobJson = new JSONObject();
                            try {
                                blobJson.put("type", "blob");
                            } catch (JSONException ignored) {}
                            row.put(blobJson);
                            break;
                        case Cursor.FIELD_TYPE_NULL:
                            row.put(null);
                            break;
                        default:
                            row.put(cursor.getString(i));
                    }
                }
                json.put(row);
            } while (cursor.moveToNext());
        }
        return json;
    }
}
