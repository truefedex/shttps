package com.phlox.server.database;

import com.phlox.server.database.model.TableDataImpl;
import com.phlox.server.utils.InputStreamWithDependency;
import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.simpleserver.database.AbortableDatabaseOperations;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.DatabaseExecutorSupport;
import com.phlox.simpleserver.database.DatabaseOperations;
import com.phlox.simpleserver.database.DatabaseTransactionScope;
import com.phlox.simpleserver.database.TransactionAbortHandle;
import com.phlox.simpleserver.database.TransactionAbortedException;
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
    /**
     * True on the write executor's thread. Writes are serialized through a single thread, so a
     * write submitted from that thread would wait for a queue that only it can drain - see
     * {@link #checkNotOnWriteThread()}.
     */
    private final ThreadLocal<Boolean> onWriteThread = ThreadLocal.withInitial(() -> Boolean.FALSE);

    static final SHTTPSLoggerProxy.Logger logger = SHTTPSLoggerProxy.getLogger(DatabaseImpl.class);

    public DatabaseImpl(DataSource dataSource, String path) {
        this.dataSource = dataSource;
        this.path = path;
        this.writeExecutor = Executors.newSingleThreadExecutor();
        this.simpleDBOperations = new SimpleDatabaseOperations(null);
    }

    /**
     * Runs a write on the single write thread and waits for it, rethrowing what it threw unwrapped.
     */
    private <T> T submitWrite(Callable<T> task) throws Exception {
        checkNotOnWriteThread();
        return DatabaseExecutorSupport.await(writeExecutor.submit(() -> {
            onWriteThread.set(Boolean.TRUE);
            try {
                return task.call();
            } finally {
                onWriteThread.set(Boolean.FALSE);
            }
        }));
    }

    /**
     * A transaction scope runs on the write thread; calling back into this Database from there
     * would enqueue work behind the very task that is waiting for it. Fail loudly instead of
     * hanging - the scope is given its own {@link DatabaseOperations} and is meant to use that.
     */
    private void checkNotOnWriteThread() {
        if (onWriteThread.get()) {
            throw new IllegalStateException("This Database method was called from inside a " +
                    "transaction scope, which would deadlock the write thread. Use the " +
                    "DatabaseOperations passed to the scope instead.");
        }
    }

    @Override
    public String getPath() {
        return path;
    }

    @Override
    public Map<String, Object> getStatus() throws Exception {
        Map<String, Object> status = new HashMap<>();
        status.put("path", path);
        //an in-memory database has no file, and nothing here is worth an NPE over a missing size
        status.put("size", path == null || path.isEmpty() ? 0L : new File(path).length());
        int tablesCount = 0;
        String sqliteVersion = null;
        try (Connection connection = provideConnection()) {
            Statement statement = connection.createStatement();
            ResultSet rs = statement.executeQuery("SELECT COUNT(name) FROM sqlite_master WHERE type='table' AND name!='android_metadata' AND name NOT LIKE 'sqlite_%'");
            if (rs.next()) {
                tablesCount = rs.getInt(1);
            }
            //after the count has been read: re-executing a Statement closes its previous ResultSet
            rs = statement.executeQuery("SELECT sqlite_version()");
            if (rs.next()) {
                sqliteVersion = rs.getString(1);
            }
        }
        status.put("tablesCount", tablesCount);
        if (sqliteVersion != null) {
            status.put("sqliteVersion", sqliteVersion);
        }
        return status;
    }

    @Override
    public Table[] getTables() throws Exception {
        return simpleDBOperations.getTables();
    }

    @Override
    public TableData query(String query, Object[] args, boolean possiblyWriteOperation) throws Exception {
        if (possiblyWriteOperation) {
            return submitWrite(() -> simpleDBOperations.query(query));
        }
        return simpleDBOperations.query(query);
    }

    @Override
    public void execute(String query) throws Exception {
        submitWrite(() -> {
            simpleDBOperations.execute(query);
            return 0;
        });
    }

    private Table[] readTables(Connection connection) throws Exception {
        ArrayList<Table> tables = new ArrayList<>();

        //inside a transaction the connection outlives this call, so the statement is closed here
        //rather than left to the connection
        try (Statement statement = connection.createStatement()) {
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
        return submitWrite(() -> simpleDBOperations.insert(tableName, values));
    }

    @Override
    public int update(String tableName, JSONObject values, String[] whereFilters, Object[] whereArgs) throws Exception {
        return submitWrite(() -> simpleDBOperations.update(tableName, values, whereFilters, whereArgs));
    }

    @Override
    public int delete(String tableName, String[] whereFilters, Object[] whereArgs) throws Exception {
        return submitWrite(() -> simpleDBOperations.delete(tableName, whereFilters, whereArgs));
    }

    @Override
    public void close() throws Exception {
        //the write thread belongs to this instance; without this every database swap leaks one
        writeExecutor.shutdownNow();
    }

    @Override
    public <T> T runTransaction(DatabaseTransactionScope<T> tx) throws Exception {
        return runTransaction(tx, new TransactionAbortHandle());
    }

    @Override
    public <T> T runTransaction(DatabaseTransactionScope<T> tx, TransactionAbortHandle abortHandle) throws Exception {
        Callable<T> task = () -> {
            //the handle interrupts this thread to wake a scope that is waiting rather than working
            abortHandle.attachWorker(Thread.currentThread());
            try {
                TransactionConnection transaction = new TransactionConnection(provideConnection());
                try {
                    Connection connection = transaction.connection;
                    try {
                        connection.setAutoCommit(false);
                        T result;
                        try {
                            //a per-scope wrapper, never shared: the handle belongs to this
                            //transaction
                            result = tx.execute(new AbortableDatabaseOperations(
                                    new SimpleDatabaseOperations(transaction), abortHandle));
                            //an abort that landed after the last operation must not be committed
                            abortHandle.checkNotAborted();
                        } finally {
                            //the scope is over, so stop taking interrupts and clear any that
                            //arrived: the commit or rollback below must not be interrupted itself
                            abortHandle.detachWorker();
                        }
                        connection.commit();
                        return result;
                    } catch (Exception e) {
                        connection.rollback();
                        throw e;
                    }
                } finally {
                    transaction.release();
                }
            } catch (Exception e) {
                //whatever ended the scope, an aborted transaction is reported as such: a scope
                //parked on a queue comes back with InterruptedException, one between statements
                //with TransactionAbortedException
                if (abortHandle.isAborted()) {
                    throw new TransactionAbortedException(abortHandle.getReason(), e);
                }
                throw e;
            } finally {
                //no-op when the scope already detached; here for the paths that never reached it,
                //and to make sure no interrupt is left set for the next task in the queue
                abortHandle.detachWorker();
            }
        };
        return submitWrite(task);
    }

    private Connection provideConnection() throws SQLException {
        //journal_mode / foreign_keys are applied by the driver at connection-open time,
        //see DatabaseFabricImpl
        return dataSource.getConnection();
    }

    /**
     * Reference-counted owner of a transaction connection.
     * <p>
     * Results produced inside a transaction may outlive it: streamed responses (see
     * DBCustomSQLRequestHandler and DBSingleCellDataRequestHandler) are generated on a separate
     * thread, after runTransaction() has already returned. A ResultSet can not be read once its
     * connection is closed (sqlite throws "stmt pointer is closed"), so the connection is closed
     * by whoever finishes last: the transaction itself or the last result still being read.
     * Committing the transaction does not invalidate those results, only closing does.
     */
    private static class TransactionConnection {
        private final Connection connection;
        private int users = 1;//the transaction itself

        TransactionConnection(Connection connection) {
            this.connection = connection;
        }

        synchronized Connection acquire() {
            if (users == 0) {
                throw new IllegalStateException("Transaction connection is already closed");
            }
            users++;
            return connection;
        }

        synchronized void release() throws SQLException {
            if (--users == 0) {
                connection.close();
            }
        }
    }

    public static class ManagedConnection implements AutoCloseable {
        private final Connection delegate;
        private final TransactionConnection transaction;//null if not a part of a transaction
        private boolean closed = false;

        ManagedConnection(Connection delegate) {
            this.delegate = delegate;
            this.transaction = null;
        }

        ManagedConnection(TransactionConnection transaction) {
            this.delegate = transaction.acquire();
            this.transaction = transaction;
        }

        public Connection unwrap() {
            return delegate;
        }

        @Override
        public void close() throws SQLException {
            //close() is called more than once on some paths (e.g. execute() closes it explicitly
            //and then again by try-with-resources), so it must stay idempotent to keep the
            //transaction connection reference count correct
            if (closed) return;
            closed = true;
            if (transaction != null) {
                transaction.release();
            } else {
                delegate.close();
            }
        }
    }

    public class SimpleDatabaseOperations implements DatabaseOperations {
        private final TransactionConnection transaction;

        private SimpleDatabaseOperations(TransactionConnection transaction) {
            this.transaction = transaction;
        }

        private ManagedConnection provideConnection() throws SQLException {
            return this.transaction != null ? new ManagedConnection(this.transaction) :
                    new ManagedConnection(DatabaseImpl.this.provideConnection());
        }

        @Override
        public Table[] getTables() throws Exception {
            try (ManagedConnection connection = provideConnection()) {
                return readTables(connection.unwrap());
            }
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
            //the total is the count of everything matching, so it is taken before the ordering and
            //the page window are appended: count(*) returns a single row, which any OFFSET would
            //skip - leaving the caller with a total of zero on every page but the first
            String countSql = sql.toString();
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
                String sqlString = String.format(countSql, "count(*)");
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

            //length(CAST(x AS BLOB)) rather than length(x): the value is answered as bytes, and
            //length() counts characters for a text value - so anything outside ASCII would be
            //announced shorter than it is and the client would stop reading too early. On a blob
            //the cast changes nothing.
            StringBuilder sql = new StringBuilder("SELECT typeof(")
                    .append("\"").append(column).append("\"").append("), length(CAST(")
                    .append("\"").append(column).append("\"").append(" AS BLOB)), ")
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
