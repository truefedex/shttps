package com.phlox.simpleserver.database;

import com.phlox.simpleserver.database.model.TableData;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A {@link TableData} over rows held in memory, for tests that need a cursor without a database.
 * Records whether it was closed, which several tests assert on.
 */
public class FakeTableData implements TableData {
    private final String[] columnNames;
    private final List<Object[]> rows;
    private int position = -1;
    private boolean closed = false;
    /** When set, {@link #next()} throws it once the given row has been read. */
    private RuntimeException failAfterRows = null;
    private int failAfterRowCount = -1;

    public FakeTableData(String[] columnNames, List<Object[]> rows) {
        this.columnNames = columnNames;
        this.rows = rows;
    }

    public static FakeTableData empty(String... columnNames) {
        return new FakeTableData(columnNames, new ArrayList<>());
    }

    public static FakeTableData of(String[] columnNames, Object[]... rows) {
        return new FakeTableData(columnNames, new ArrayList<>(Arrays.asList(rows)));
    }

    /** Makes the cursor blow up part way through, to check that callers still close it. */
    public FakeTableData failAfter(int rowCount, RuntimeException error) {
        this.failAfterRowCount = rowCount;
        this.failAfterRows = error;
        return this;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public boolean next() {
        if (failAfterRows != null && position + 1 >= failAfterRowCount) {
            throw failAfterRows;
        }
        position++;
        return position < rows.size();
    }

    @Override
    public boolean skip(int count) {
        position += count;
        return position < rows.size();
    }

    @Override
    public String[] getColumnNames() {
        return columnNames;
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public int getColumnIndex(String columnName) {
        for (int i = 0; i < columnNames.length; i++) {
            if (columnNames[i].equals(columnName)) return i;
        }
        return -1;
    }

    private Object value(int columnIndex) {
        return rows.get(position)[columnIndex];
    }

    @Override
    public String getString(int columnIndex) {
        Object v = value(columnIndex);
        return v == null ? null : v.toString();
    }

    @Override
    public int getInt(int columnIndex) {
        return ((Number) value(columnIndex)).intValue();
    }

    @Override
    public long getLong(int columnIndex) {
        return ((Number) value(columnIndex)).longValue();
    }

    @Override
    public double getDouble(int columnIndex) {
        return ((Number) value(columnIndex)).doubleValue();
    }

    @Override
    public byte[] getBlob(int columnIndex) {
        return (byte[]) value(columnIndex);
    }

    @Override
    public boolean getBoolean(int columnIndex) {
        return (Boolean) value(columnIndex);
    }

    @Override
    public Integer getInt(int columnIndex, Integer defaultValue) {
        Object v = value(columnIndex);
        return v == null ? defaultValue : ((Number) v).intValue();
    }

    @Override
    public Long getLong(int columnIndex, Long defaultValue) {
        Object v = value(columnIndex);
        return v == null ? defaultValue : ((Number) v).longValue();
    }

    @Override
    public Double getDouble(int columnIndex, Double defaultValue) {
        Object v = value(columnIndex);
        return v == null ? defaultValue : ((Number) v).doubleValue();
    }

    @Override
    public Boolean getBoolean(int columnIndex, Boolean defaultValue) {
        Object v = value(columnIndex);
        return v == null ? defaultValue : (Boolean) v;
    }

    @Override
    public boolean isNull(int col) {
        return value(col) == null;
    }

    @Override
    public JSONArray toJson(boolean rowsAsObjects) {
        JSONArray result = new JSONArray();
        while (next()) {
            result.put(rowsAsObjects ? currentRowToJsonObject() : currentRowToJson());
        }
        return result;
    }

    @Override
    public JSONArray currentRowToJson() {
        JSONArray row = new JSONArray();
        for (int i = 0; i < columnNames.length; i++) {
            row.put(value(i) == null ? JSONObject.NULL : value(i));
        }
        return row;
    }

    @Override
    public JSONObject currentRowToJsonObject() {
        JSONObject row = new JSONObject();
        for (int i = 0; i < columnNames.length; i++) {
            row.put(columnNames[i], value(i) == null ? JSONObject.NULL : value(i));
        }
        return row;
    }
}
