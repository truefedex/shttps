package com.phlox.server.database;

import com.phlox.server.database.model.TableDataImpl;
import com.phlox.server.utils.InputStreamWithDependency;
import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.model.Column;
import com.phlox.simpleserver.database.model.Table;
import com.phlox.simpleserver.database.model.TableData;
import com.phlox.simpleserver.database.utils.DBUtils;

import org.json.JSONObject;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

public class DatabaseImpl implements Database {
    private DataSource dataSource;
    private String path;

    static final SHTTPSLoggerProxy.Logger logger = SHTTPSLoggerProxy.getLogger(DatabaseImpl.class);

    public DatabaseImpl(DataSource dataSource, String path) {
        this.dataSource = dataSource;
        this.path = path;
    }

    @Override
    public String getPath() {
        return path;
    }

    @Override
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("path", path);
        status.put("size", new File(path).length());
        int tablesCount = 0;
        try (Connection connection = dataSource.getConnection()) {
            Statement statement = connection.createStatement();
            ResultSet rs = statement.executeQuery("SELECT COUNT(name) FROM sqlite_master WHERE type='table' AND name!='android_metadata' AND name NOT LIKE 'sqlite_%'");
            if (rs.next()) {
                tablesCount = rs.getInt(1);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        status.put("tablesCount", tablesCount);
        return status;
    }

    @Override
    public Table[] getTables() throws Exception {
        ArrayList<Table> tables = new ArrayList<>();

        try (Connection connection = dataSource.getConnection()) {
            Statement statement = connection.createStatement();
            //get indexes and foreign keys
            ResultSet rsIndexes = statement.executeQuery("SELECT name, tbl_name, sql FROM sqlite_master WHERE type='index'");
            Map<String, String> indexes = new HashMap<>();
            while (rsIndexes.next()) {
                String name = rsIndexes.getString(1);
                String table = rsIndexes.getString(2);
                String sql = rsIndexes.getString(3);
                if (sql != null) {
                    String column = sql.substring(sql.indexOf("(") + 1, sql.indexOf(")"));
                    if (column.startsWith("[") && column.endsWith("]")) {
                        column = column.substring(1, column.length() - 1);
                    }
                    indexes.put(table + "." + column, name);
                }
            }
            rsIndexes.close();
            ResultSet rsForeignKeys = statement.executeQuery("PRAGMA foreign_key_list");
            Map<String, String> foreignKeys = new HashMap<>();
            while (rsForeignKeys.next()) {
                String table = rsForeignKeys.getString(1);
                String column = rsForeignKeys.getString(2);
                String foreignTable = rsForeignKeys.getString(3);
                String foreignColumn = rsForeignKeys.getString(4);
                foreignKeys.put(table + "." + column, foreignTable + "." + foreignColumn);
            }
            rsForeignKeys.close();

            //get tables
            ResultSet rs = statement.executeQuery("""
                    SELECT name,
                    CASE
                    	WHEN sql LIKE '%WITHOUT ROWID%' THEN 0
                    	ELSE 1
                    END AS has_rowid, sql
                    FROM sqlite_master WHERE type='table' AND name!='android_metadata' AND name NOT LIKE 'sqlite_%'
                    """);
            while (rs.next()) {
                Table table = new Table();
                table.name = rs.getString(1);
                table.hasRowId = rs.getInt(2) == 1;
                table.sql = rs.getString(3);
                tables.add(table);
            }
            rs.close();

            for (Table table : tables) {
                ResultSet rsColumns = statement.executeQuery("PRAGMA table_info(" + table.name + ")");
                ArrayList<Column> columns = new ArrayList<>();
                while (rsColumns.next()) {
                    Column column = new Column();
                    column.name = rsColumns.getString(2);
                    column.type = rsColumns.getString(3);
                    column.notNull = rsColumns.getInt(4) == 1;
                    column.defaultValue = rsColumns.getString(5);
                    column.primaryKey = rsColumns.getInt(6) > 0;
                    column.autoIncrement = column.primaryKey && table.sql.contains("AUTOINCREMENT");
                    columns.add(column);
                }
                rsColumns.close();

                //connect with indexes and foreign keys
                for (Column column : columns) {
                    column.index = indexes.get(table.name + "." + column.name);
                    column.foreignKey = foreignKeys.get(table.name + "." + column.name);
                }

                table.columns = columns.toArray(new Column[0]);

                //get row count
                ResultSet rsRowCount = statement.executeQuery("SELECT COUNT(*) FROM " + table.name);
                if (rsRowCount.next()) {
                    table.rowCount = rsRowCount.getLong(1);
                }
            }
        }

        return tables.toArray(new Table[0]);
    }

    @Override
    public TableData execute(String query) throws Exception {
        Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        try {
            ResultSet resultSet = statement.executeQuery(query);
            return new TableDataImpl(connection, statement, resultSet);
        } catch (Exception e) {
            connection.close();
            if (e instanceof SQLException && ((SQLException)e).getErrorCode() == 101) {//SQLITE_DONE
                return null;//no data
            } else {
                throw e;
            }
        }
    }

    @Override
    public TableData getTableDataSecure(String tableName, String[] columns, Long offset, Long limit,
                                        String[] whereFilters, Object[] whereArgs, String orderBy,
                                        boolean desc, boolean includeRowId) throws Exception {
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

        if (whereFilters != null && whereFilters.length > 0) {
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

        logger.d("SQL: " + sql);

        Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql.toString());
        if (whereArgs != null) {
            for (int i = 0; i < whereArgs.length; i++) {
                Object value = whereArgs[i];
                try {
                    setStatementValue(statement, i + 1, value);
                } catch (Exception e) {
                    connection.close();
                    throw new RuntimeException(e);
                }
            }
        }

        try {
            ResultSet rs = statement.executeQuery();
            return new TableDataImpl(connection, statement, rs);
        } catch (SQLException e) {
            connection.close();
            throw new RuntimeException(e);
        }
    }

    @Override
    public CellDataStreamInfo getSingleCellDataStream(String table, String column, List<String> filters, List<Object> filtersArgs) throws Exception {
        if (!DBUtils.isValidTableName(table)) {
            throw new SecurityException("Invalid table name: " + table);
        }
        if (!DBUtils.isValidColumnName(column)) {
            throw new SecurityException("Invalid column name: " + column);
        }

        StringBuilder sql = new StringBuilder("SELECT typeof(")
                .append(column).append("), length(")
                .append(column).append("), ")
                .append(column).append(" FROM ").append(table);
        if (filters != null && !filters.isEmpty()) {
            String where = DBUtils.buildSimpleWhereStatement(filters.toArray(new String[0]));
            sql.append(" WHERE ").append(where);
        }

        sql.append(" LIMIT 1");

        logger.d("SQL: " + sql);

        Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql.toString());
        if (filtersArgs != null) {
            for (int i = 0; i < filtersArgs.size(); i++) {
                Object value = filtersArgs.get(i);
                setStatementValue(statement, i + 1, value);
            }
        }

        ResultSet rs = statement.executeQuery();
        if (rs.next()) {
            CellDataStreamInfo cellDataStreamInfo = new CellDataStreamInfo();
            cellDataStreamInfo.type = rs.getString(1);
            if (cellDataStreamInfo.type.equals("text") || cellDataStreamInfo.type.equals("blob")) {
                cellDataStreamInfo.length = rs.getLong(2);
                cellDataStreamInfo.inputStream = new InputStreamWithDependency(rs.getBinaryStream(3), connection);
                cellDataStreamInfo.mimeType = cellDataStreamInfo.type.equals("text") ? "text/plain" : "application/octet-stream";
                return cellDataStreamInfo;
            } else if (cellDataStreamInfo.type.equals("null")) {
                connection.close();
                return cellDataStreamInfo;
            } else {
                connection.close();
                throw new IllegalArgumentException("Invalid column type: " + cellDataStreamInfo.type + ", only text and blob are supported");
            }
        } else {
            connection.close();
            return null;
        }
    }

    @Override
    public long insert(String tableName, JSONObject values) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            StringBuilder sql = new StringBuilder("INSERT INTO ");
            if (!DBUtils.isValidTableName(tableName)) {
                throw new SecurityException("Invalid table name: " + tableName);
            }
            sql.append(tableName).append(" (");
            int columnsCount = 0;
            StringBuilder valuesSql = new StringBuilder(") VALUES (");
            for (Iterator<String> it = values.keys(); it.hasNext();) {
                String column = it.next();
                if (!DBUtils.isValidColumnName(column)) {
                    throw new SecurityException("Invalid column name: " + column);
                }
                if (columnsCount++ > 0) {
                    sql.append(", ");
                    valuesSql.append(", ");
                }
                sql.append(column);
                valuesSql.append("?");
            }
            sql.append(valuesSql).append(")");

            logger.d("SQL: " + sql);

            PreparedStatement statement = connection.prepareStatement(sql.toString(), Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            for (Iterator<String> it = values.keys(); it.hasNext();) {
                Object value = values.get(it.next());
                setStatementValue(statement, i, value);
                i++;
            }

            statement.executeUpdate();
            ResultSet rs = statement.getGeneratedKeys();
            if (rs.next()) {
                return rs.getLong(1);
            } else {
                //no generated keys
                return -1;
            }
        }
    }

    @Override
    public int update(String tableName, JSONObject values, String[] whereFilters, Object[] whereArgs) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            StringBuilder sql = new StringBuilder("UPDATE ");
            if (!DBUtils.isValidTableName(tableName)) {
                throw new SecurityException("Invalid table name: " + tableName);
            }
            sql.append(tableName).append(" SET ");
            for (Iterator<String> it = values.keys(); it.hasNext();) {
                String column = it.next();
                if (!DBUtils.isValidColumnName(column)) {
                    throw new SecurityException("Invalid column name: " + column);
                }
                if (sql.charAt(sql.length() - 1) != ' ') {
                    sql.append(", ");
                }
                sql.append(column).append(" = ?");
            }

            if (whereFilters != null && whereFilters.length > 0) {
                String where = DBUtils.buildSimpleWhereStatement(whereFilters);
                sql.append(" WHERE ").append(where);
            }

            PreparedStatement statement = connection.prepareStatement(sql.toString());
            int i = 1;
            for (Iterator<String> it = values.keys(); it.hasNext();) {
                Object value = values.get(it.next());
                setStatementValue(statement, i, value);
                i++;
            }

            if (whereArgs != null) {
                for (Object value : whereArgs) {
                    setStatementValue(statement, i, value);
                    i++;
                }
            }

            logger.d("SQL: " + statement.toString());

            return statement.executeUpdate();
        }
    }

    @Override
    public int delete(String tableName, String[] whereFilters, Object[] whereArgs) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            StringBuilder sql = new StringBuilder("DELETE FROM ");
            if (!DBUtils.isValidTableName(tableName)) {
                throw new SecurityException("Invalid table name: " + tableName);
            }
            sql.append(tableName);

            if (whereFilters != null && whereFilters.length > 0) {
                String where = DBUtils.buildSimpleWhereStatement(whereFilters);
                sql.append(" WHERE ").append(where);
            }

            logger.d("SQL: " + sql);

            PreparedStatement statement = connection.prepareStatement(sql.toString());
            if (whereArgs != null && whereArgs.length > 0) {
                for (int i = 0; i < whereArgs.length; i++) {
                    Object value = whereArgs[i];
                    setStatementValue(statement, i + 1, value);
                }
            }

            return statement.executeUpdate();
        }
    }

    @Override
    public void close() throws Exception {
        //do nothing
    }

    private static void setStatementValue(PreparedStatement statement, int i, Object value) throws Exception {
        if (value == null || value.equals(JSONObject.NULL)) {
            statement.setNull(i, java.sql.Types.NULL);
        } else if (value instanceof Long) {
            statement.setLong(i, (Long) value);
        } else if (value instanceof Integer) {
            statement.setInt(i, (Integer) value);
        } else if (value instanceof Short) {
            statement.setShort(i, (Short) value);
        } else if (value instanceof Float) {
            statement.setFloat(i, (Float) value);
        } else if (value instanceof Double) {
            statement.setDouble(i, (Double) value);
        } else if (value instanceof Boolean) {
            statement.setBoolean(i, (Boolean) value);
        } else if (value instanceof JSONObject && ((JSONObject) value).has("type") &&
                ((JSONObject) value).opt("type").equals("blob") &&
                ((JSONObject) value).has("value")) {
            String base64 = ((JSONObject) value).opt("value").toString();
            statement.setBytes(i, Base64.getDecoder().decode(base64));
        } else {
            statement.setString(i, value.toString());
        }
    }
}
