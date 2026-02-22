package com.phlox.server.database;

import com.phlox.server.database.model.TableDataImpl;
import com.phlox.server.utils.InputStreamWithDependency;
import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.DatabaseOperations;
import com.phlox.simpleserver.database.DatabaseTransactionScope;
import com.phlox.simpleserver.database.model.Column;
import com.phlox.simpleserver.database.model.Table;
import com.phlox.simpleserver.database.model.TableData;
import com.phlox.simpleserver.database.utils.DBUtils;
import com.phlox.simpleserver.utils.Holder;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.sql.DataSource;

public class DatabaseImpl implements Database {
    private final DataSource dataSource;
    private final String path;
    private final ExecutorService writeExecutor;
    private final SimpleDatabaseOperations simpleDBOperations;

    static final SHTTPSLoggerProxy.Logger logger = SHTTPSLoggerProxy.getLogger(DatabaseImpl.class);

    public DatabaseImpl(DataSource dataSource, String path) {
        this.dataSource = dataSource;
        this.path = path;
        this.writeExecutor = Executors.newSingleThreadExecutor();
        this.simpleDBOperations = new SimpleDatabaseOperations(null);
    }

    @Override
    public String getPath() {
        return path;
    }

    @Override
    public Map<String, Object> getStatus() throws Exception {
        Map<String, Object> status = new HashMap<>();
        status.put("path", path);
        status.put("size", new File(path).length());
        int tablesCount = 0;
        try (Connection connection = provideConnection()) {
            Statement statement = connection.createStatement();
            ResultSet rs = statement.executeQuery("SELECT COUNT(name) FROM sqlite_master WHERE type='table' AND name!='android_metadata' AND name NOT LIKE 'sqlite_%'");
            if (rs.next()) {
                tablesCount = rs.getInt(1);
            }
        }
        status.put("tablesCount", tablesCount);
        return status;
    }

    @Override
    public Table[] getTables() throws Exception {
        ArrayList<Table> tables = new ArrayList<>();

        try (Connection connection = provideConnection()) {
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
                    FROM sqlite_master WHERE type='table' AND name!='android_metadata' AND name!='shttps_version' AND name NOT LIKE 'sqlite_%'
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
    public TableData query(String query, Object[] args, boolean possiblyWriteOperation) throws Exception {
        if (possiblyWriteOperation) {
            return writeExecutor.submit(() -> simpleDBOperations.query(query)).get();
        }
        return simpleDBOperations.query(query);
    }

    @Override
    public void execute(String query) throws Exception {
        writeExecutor.submit(() -> {
            simpleDBOperations.execute(query);
            return 0;
        }).get();
    }

    @Override
    public TableData getTableDataSecure(String tableName, String[] columns, Long offset, Long limit,
                                        String[] whereFilters, Object[] whereArgs, String orderBy,
                                        boolean desc, boolean includeRowId, Holder<Long> outCount) throws Exception {
        return simpleDBOperations.getTableDataSecure(tableName, columns, offset, limit, whereFilters, whereArgs, orderBy, desc, includeRowId, outCount);
    }

    @Override
    public CellDataStreamInfo getSingleCellDataStream(String table, String column, List<String> filters, List<Object> filtersArgs) throws Exception {
        return simpleDBOperations.getSingleCellDataStream(table, column, filters, filtersArgs);
    }

    @Override
    public long insert(String tableName, JSONObject values) throws Exception {
        return writeExecutor.submit(() ->
                simpleDBOperations.insert(tableName, values)
        ).get();
    }

    @Override
    public int update(String tableName, JSONObject values, String[] whereFilters, Object[] whereArgs) throws Exception {
        return writeExecutor.submit(() ->
                simpleDBOperations.update(tableName, values, whereFilters, whereArgs)
        ).get();
    }

    @Override
    public int delete(String tableName, String[] whereFilters, Object[] whereArgs) throws Exception {
        return writeExecutor.submit(() ->
                simpleDBOperations.delete(tableName, whereFilters, whereArgs)
        ).get();
    }

    @Override
    public void close() throws Exception {
        //do nothing
    }

    @Override
    public <T> T runTransaction(DatabaseTransactionScope<T> tx) throws Exception {
        Callable<T> task = () -> {
            try (Connection connection = provideConnection()){
                try {
                    connection.setAutoCommit(false);
                    T result = tx.execute(new SimpleDatabaseOperations(connection));
                    connection.commit();
                    return result;
                } catch (Exception e) {
                    connection.rollback();
                    throw e;
                }
            }
        };
        return writeExecutor.submit(task).get();
    }

    private Connection provideConnection() throws SQLException {
        Connection connection = dataSource.getConnection();
        try (PreparedStatement statement = connection.prepareStatement("PRAGMA journal_mode=wal")) {
            statement.execute();
        }
        return connection;
    }

    public static class ManagedConnection implements AutoCloseable {
        private final Connection delegate;
        private final boolean transactional;

        ManagedConnection(Connection delegate, boolean transactional) {
            this.delegate = delegate;
            this.transactional = transactional;
        }

        public Connection unwrap() {
            return delegate;
        }

        @Override
        public void close() throws SQLException {
            if (!transactional) {
                delegate.close();
            }
            // if transactional - do nothing
        }
    }

    public class SimpleDatabaseOperations implements DatabaseOperations {
        private final Connection connection;

        private SimpleDatabaseOperations(Connection connection) {
            this.connection = connection;
        }

        private ManagedConnection provideConnection() throws SQLException {
            Connection conn = this.connection != null ? this.connection : DatabaseImpl.this.provideConnection();
            return new ManagedConnection(conn, this.connection != null);
        }

        @Override
        public TableData query(String query, Object[] args, boolean possiblyWriteOperation) throws Exception {
            ManagedConnection connection = provideConnection();

            Statement statement;
            if (args != null && args.length > 0) {
                PreparedStatement preparedStatement = connection.unwrap().prepareStatement(query);
                for (int i = 0; i < args.length; i++) {
                    Object value = args[i];
                    setStatementValue(preparedStatement, i + 1, value);
                }
                statement = preparedStatement;
            } else {
                statement = connection.unwrap().createStatement();
            }
            try {
                ResultSet resultSet = statement instanceof PreparedStatement ?
                            ((PreparedStatement)statement).executeQuery()    :
                            statement.executeQuery(query);
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
        public void execute(String query) throws Exception {
            try (ManagedConnection connection = provideConnection()) {
                Statement statement = connection.unwrap().createStatement();
                try {
                    statement.execute(query);
                } catch (Exception e) {
                    connection.close();
                }
            }
        }

        @Override
        public long insert(String tableName, JSONObject values) throws Exception {
            try (ManagedConnection connection = provideConnection()) {
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
                    sql.append("\"").append(column).append("\"");
                    valuesSql.append("?");
                }
                sql.append(valuesSql).append(")");

                PreparedStatement statement = connection.unwrap().prepareStatement(sql.toString(), Statement.RETURN_GENERATED_KEYS);
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
            try (ManagedConnection connection = provideConnection()) {
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
                    sql.append("\"").append(column).append("\"").append(" = ?");
                }

                if (whereFilters != null && whereFilters.length > 0) {
                    String where = DBUtils.buildSimpleWhereStatement(whereFilters);
                    sql.append(" WHERE ").append(where);
                }

                PreparedStatement statement = connection.unwrap().prepareStatement(sql.toString());
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

                return statement.executeUpdate();
            }
        }

        @Override
        public int delete(String tableName, String[] whereFilters, Object[] whereArgs) throws Exception {
            try (ManagedConnection connection = provideConnection()) {
                StringBuilder sql = new StringBuilder("DELETE FROM ");
                if (!DBUtils.isValidTableName(tableName)) {
                    throw new SecurityException("Invalid table name: " + tableName);
                }
                sql.append(tableName);

                if (whereFilters != null && whereFilters.length > 0) {
                    String where = DBUtils.buildSimpleWhereStatement(whereFilters);
                    sql.append(" WHERE ").append(where);
                }

                PreparedStatement statement = connection.unwrap().prepareStatement(sql.toString());
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
        public TableData getTableDataSecure(String tableName, String[] columns, Long offset, Long limit, String[] whereFilters, Object[] whereArgs, String orderBy, boolean desc, boolean includeRowId, Holder<Long> outCount) throws Exception {
            StringBuilder sql = new StringBuilder("SELECT %s");

            StringBuilder columnsStringBuilder = new StringBuilder();
            if (includeRowId) {
                columnsStringBuilder.append("rowid, ");
            }
            if (columns == null || columns.length == 0) {
                columnsStringBuilder.append("*");
            } else {
                for (int i = 0; i < columns.length; i++) {
                    if (i > 0) {
                        columnsStringBuilder.append(", ");
                    }
                    String column = columns[i];
                    if (DBUtils.isValidColumnName(column)) {
                        columnsStringBuilder.append("\"").append(column).append("\"");
                    } else {
                        throw new SecurityException("Invalid column name: " + column);
                    }
                }
            }
            String columnsString = columnsStringBuilder.toString();

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
                sql.append(" ORDER BY ").append("\"").append(orderBy).append("\"");
                if (desc) {
                    sql.append(" DESC");
                } else {
                    sql.append(" ASC");
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

            ManagedConnection connection = provideConnection();

            if (outCount != null) {
                String sqlString = String.format(sql.toString(), "count(*)");
                PreparedStatement statement = connection.unwrap().prepareStatement(sqlString);
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
                try (ResultSet rs = statement.executeQuery()) {
                    outCount.set(rs.next() ? rs.getLong(1) : 0);
                } catch (SQLException e) {
                    connection.close();
                    throw new RuntimeException(e);
                }
            }

            String sqlString = String.format(sql.toString(), columnsString);
            PreparedStatement statement = connection.unwrap().prepareStatement(sqlString);
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
                    .append("\"").append(column).append("\"").append("), length(")
                    .append(column).append("), ")
                    .append("\"").append(column).append("\"").append(" FROM ").append(table);
            if (filters != null && !filters.isEmpty()) {
                String where = DBUtils.buildSimpleWhereStatement(filters.toArray(new String[0]));
                sql.append(" WHERE ").append(where);
            }

            sql.append(" LIMIT 1");

            ManagedConnection connection = provideConnection();
            PreparedStatement statement = connection.unwrap().prepareStatement(sql.toString());
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
