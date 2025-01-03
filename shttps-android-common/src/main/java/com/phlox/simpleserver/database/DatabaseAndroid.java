package com.phlox.simpleserver.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.phlox.simpleserver.database.model.Column;
import com.phlox.simpleserver.database.model.Table;
import com.phlox.simpleserver.database.model.TableData;
import com.phlox.simpleserver.database.model.TableDataAndroid;
import com.phlox.simpleserver.database.utils.DBUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class DatabaseAndroid extends SQLiteOpenHelper implements Database {
    private final String path;

    public DatabaseAndroid(Context context, File directory, String name) {
        super(new SHTTPSDatabaseContext(context, directory), name, null, 1);
        this.path = new File(directory, name).getAbsolutePath();
    }

    @Override
    public String getPath() {
        return path;
    }

    @Override
    public Map<String, Object> getStatus() throws IOException {
        SQLiteDatabase database = getReadableDatabase();
        Map<String, Object> status = new HashMap<>();
        status.put("path", path);
        status.put("size", new File(path).length());
        Cursor cursor = database.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name!='android_metadata' AND name NOT LIKE 'sqlite_%'", null);
        status.put("tablesCount", cursor.getCount());
        cursor.close();
        database.close();
        return status;
    }

    @Override
    public Table[] getTables() throws Exception {
        SQLiteDatabase database = getReadableDatabase();
        //get indexes and foreign keys
        Cursor cursorIndexes = database.rawQuery("SELECT name, tbl_name, sql FROM sqlite_master WHERE type='index'", null);
        Map<String, String> indexes = new HashMap<>();
        while (cursorIndexes.moveToNext()) {
            String name = cursorIndexes.getString(0);
            String table = cursorIndexes.getString(1);
            String sql = cursorIndexes.getString(2);
            if (sql != null) {
                String column = sql.substring(sql.indexOf("(") + 1, sql.indexOf(")"));
                if (column.startsWith("[") && column.endsWith("]")) {
                    column = column.substring(1, column.length() - 1);
                }
                indexes.put(table + "." + column, name);
            }
        }
        cursorIndexes.close();
        Cursor cursorForeignKeys = database.rawQuery("PRAGMA foreign_key_list", null);
        Map<String, String> foreignKeys = new HashMap<>();
        while (cursorForeignKeys.moveToNext()) {
            String table = cursorForeignKeys.getString(0);
            String column = cursorForeignKeys.getString(1);
            String foreignTable = cursorForeignKeys.getString(2);
            String foreignColumn = cursorForeignKeys.getString(3);
            foreignKeys.put(table + "." + column, foreignTable + "." + foreignColumn);
        }
        cursorForeignKeys.close();

        //get tables
        Cursor cursor = database.rawQuery(
            "SELECT name,\n" +
            "CASE\n" +
            "    WHEN sql LIKE '%WITHOUT ROWID%' THEN 0\n" +
            "    ELSE 1\n" +
            "END AS has_rowid\n" +
            "FROM sqlite_master WHERE type='table' AND name!='android_metadata' AND name NOT LIKE 'sqlite_%'\n",
 null);
        ArrayList<Table> tables = new ArrayList<>();
        while (cursor.moveToNext()) {
            Table table = new Table();
            table.name = cursor.getString(0);
            table.hasRowId = cursor.getInt(1) > 0;
            Cursor cursorColumns = database.rawQuery("PRAGMA table_info(" + table.name + ")", null);
            table.columns = new Column[cursorColumns.getCount()];
            int j = 0;
            while (cursorColumns.moveToNext()) {
                Column column = new Column();
                column.name = cursorColumns.getString(1);
                column.type = cursorColumns.getString(2);
                column.notNull = cursorColumns.getInt(3) == 1;
                column.defaultValue = cursorColumns.getString(4);
                column.primaryKey = cursorColumns.getInt(5) > 0;
                table.columns[j++] = column;
            }
            cursorColumns.close();

            //connect with indexes and foreign keys
            for (Column column : table.columns) {
                column.index = indexes.get(table.name + "." + column.name);
                column.foreignKey = foreignKeys.get(table.name + "." + column.name);
            }

            //get row count
            Cursor cursorRowCount = database.rawQuery("SELECT COUNT(*) FROM " + table.name, null);
            if (cursorRowCount.moveToNext()) {
                table.rowCount = cursorRowCount.getLong(0);
            }

            tables.add(table);
        }
        cursor.close();
        Table[] tablesArr = new Table[tables.size()];
        tables.toArray(tablesArr);
        return tablesArr;
    }

    @Override
    public TableData query(String query) throws Exception {
        SQLiteDatabase database = getReadableDatabase();
        Cursor cursor = database.rawQuery(query, null);
        return new TableDataAndroid(cursor);
    }

    @Override
    public ExecuteResult execute(String query) throws Exception {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            Cursor cursor = database.rawQuery(query, null);
            int updatedRows = -1;
            if (cursor.moveToNext()) {
                updatedRows = cursor.getInt(0);
            }
            cursor.close();
            long generatedId = -1;
            cursor = database.rawQuery("SELECT last_insert_rowid()", null);
            if (cursor.moveToNext()) {
                generatedId = cursor.getLong(0);
            }
            cursor.close();
            database.setTransactionSuccessful();
            return new ExecuteResult(updatedRows, generatedId);
        } finally {
            database.endTransaction();
        }
    }

    @Override
    public TableData getTableDataSecure(String tableName, String[] columns, Long offset, Long limit,
                                        String[] whereFilters, Object[] whereArgs, String orderBy,
                                        boolean desc, boolean includeRowId) throws Exception {
        SQLiteDatabase database = getReadableDatabase();
        StringBuilder sql = new StringBuilder("SELECT ");
        if (includeRowId) {
            sql.append("rowid, ");
        }
        if (columns == null || columns.length == 0) {
            sql.append("*");
        } else {
            for (int i = 0; i < columns.length; i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                String column = columns[i];
                if (DBUtils.isValidColumnName(column)) {
                    sql.append(column);
                } else {
                    throw new SecurityException("Invalid column name: " + column);
                }
            }
        }

        if (!DBUtils.isValidTableName(tableName)) {
            throw new SecurityException("Invalid table name: " + tableName);
        }
        sql.append(" FROM ").append(tableName);

        if (whereFilters != null) {
            String where = DBUtils.buildSimpleWhereStatement(whereFilters);
            sql.append(" WHERE ").append(where);
        }
        if (orderBy != null) {
            if (!DBUtils.isValidColumnName(orderBy)) {
                throw new SecurityException("Invalid column name: " + orderBy);
            }
            sql.append(" ORDER BY ").append(orderBy);
            if (desc) {
                sql.append(" DESC");
            }
        }
        if (limit != null) {
            if (limit < 0) {
                throw new IllegalArgumentException("Invalid limit: " + limit);
            }
            sql.append(" LIMIT ").append(limit);
            if (offset != null) {
                if (offset < 0) {
                    throw new IllegalArgumentException("Invalid offset: " + offset);
                }
                sql.append(" OFFSET ").append(offset);
            }
        }
        String[] whereArgsStr = new String[whereArgs.length];
        for (int i = 0; i < whereArgs.length; i++) {
            whereArgsStr[i] = whereArgs[i].toString();
        }
        Cursor cursor = database.rawQuery(sql.toString(), whereArgsStr);
        return new TableDataAndroid(cursor);
    }

    @Override
    public long insert(String tableName, Map<String, Object> values) throws Exception {
        SQLiteDatabase database = getWritableDatabase();
        return database.insert(tableName, null, toContentValues(values));
    }

    @Override
    public int update(String tableName, Map<String, Object> values, String[] whereFilters, Object[] whereArgs) throws Exception {
        SQLiteDatabase database = getWritableDatabase();
        String[] whereArgsStr = new String[whereFilters.length];
        for (int i = 0; i < whereFilters.length; i++) {
            whereArgsStr[i] = whereArgs[i].toString();
        }
        return database.update(tableName, toContentValues(values), DBUtils.buildSimpleWhereStatement(whereFilters), whereArgsStr);
    }

    @Override
    public int delete(String tableName, String[] whereFilters, Object[] whereArgs) throws Exception {
        SQLiteDatabase database = getWritableDatabase();
        String[] whereArgsStr = new String[whereFilters.length];
        for (int i = 0; i < whereFilters.length; i++) {
            whereArgsStr[i] = whereArgs[i].toString();
        }
        return database.delete(tableName, DBUtils.buildSimpleWhereStatement(whereFilters), whereArgsStr);
    }

    @Override
    public void onCreate(SQLiteDatabase sqLiteDatabase) {
        //nothing to do
    }

    @Override
    public void onUpgrade(SQLiteDatabase sqLiteDatabase, int i, int i1) {
        //nothing to do
    }

    private static ContentValues toContentValues(Map<String, Object> values) {
        ContentValues contentValues = new ContentValues();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null) {
                contentValues.putNull(key);
            } else if (value instanceof String) {
                contentValues.put(key, (String) value);
            } else if (value instanceof Integer) {
                contentValues.put(key, (Integer) value);
            } else if (value instanceof Long) {
                contentValues.put(key, (Long) value);
            } else if (value instanceof Short) {
                contentValues.put(key, (Short) value);
            } else if (value instanceof Byte) {
                contentValues.put(key, (Byte) value);
            } else if (value instanceof Float) {
                contentValues.put(key, (Float) value);
            } else if (value instanceof Double) {
                contentValues.put(key, (Double) value);
            } else if (value instanceof Boolean) {
                contentValues.put(key, (Boolean) value);
            } else if (value instanceof byte[]) {
                contentValues.put(key, (byte[]) value);
            } else {
                contentValues.put(key, value.toString());
            }
        }
        return contentValues;
    }
}
