package com.phlox.simpleserver.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Base64;

import com.phlox.simpleserver.database.model.Column;
import com.phlox.simpleserver.database.model.Table;
import com.phlox.simpleserver.database.model.TableData;
import com.phlox.simpleserver.database.model.TableDataAndroid;
import com.phlox.simpleserver.database.utils.DBUtils;
import com.phlox.simpleserver.utils.Holder;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DatabaseAndroid implements Database {
    private final String path;
    private final SQLiteDatabase database;
    private final ExecutorService writeExecutor;
    private final SimpleDatabaseOperations simpleDBOperations;
    /**
     * True on the write executor's thread. Writes are serialized through a single thread, so a
     * write submitted from that thread would wait for a queue that only it can drain - see
     * {@link #checkNotOnWriteThread()}.
     */
    private final ThreadLocal<Boolean> onWriteThread = new ThreadLocal<>();

    /**
     * How much of a cell value one query fetches: characters for a text column, bytes for a blob.
     * Kept well under the cursor window limit, which a whole large value would not fit into.
     */
    private static final int CELL_STREAM_CHUNK_UNITS = 8 * 1024;
    private static final byte[] EMPTY_PART = new byte[0];

    public DatabaseAndroid(Context context, File directory, String name) {
        File dbFile = new File(directory, name);
        this.path = dbFile.getAbsolutePath();
        this.database = SQLiteDatabase.openDatabase(path, null,
                SQLiteDatabase.CREATE_IF_NECESSARY |
                        SQLiteDatabase.OPEN_READWRITE |
                        SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING);
        this.database.setForeignKeyConstraintsEnabled(true);
        this.writeExecutor = Executors.newSingleThreadExecutor();
        this.simpleDBOperations = new SimpleDatabaseOperations();
    }

    @Override
    public String getPath() {
        return path;
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
        if (Boolean.TRUE.equals(onWriteThread.get())) {
            throw new IllegalStateException("This Database method was called from inside a " +
                    "transaction scope, which would deadlock the write thread. Use the " +
                    "DatabaseOperations passed to the scope instead.");
        }
    }

    @Override
    public Map<String, Object> getStatus() throws IOException {
        Map<String, Object> status = new HashMap<>();
        status.put("path", path);
        status.put("size", new File(path).length());
        Cursor cursor = database.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name!='android_metadata' AND name NOT LIKE 'sqlite_%'", null);
        status.put("tablesCount", cursor.getCount());
        cursor.close();
        return status;
    }

    @Override
    public void close() throws Exception {
        //the write thread belongs to this instance; without this every database swap leaks one
        writeExecutor.shutdownNow();
        database.close();
    }

    @Override
    public Table[] getTables() throws Exception {
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
            "END AS has_rowid, sql\n" +
            "FROM sqlite_master WHERE type='table' AND name!='android_metadata' AND name!='shttps_version' AND name NOT LIKE 'sqlite_%'\n",
 null);
        ArrayList<Table> tables = new ArrayList<>();
        while (cursor.moveToNext()) {
            Table table = new Table();
            table.name = cursor.getString(0);
            table.hasRowId = cursor.getInt(1) > 0;
            table.sql = cursor.getString(2);
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
                column.autoIncrement = column.primaryKey && table.sql.contains("AUTOINCREMENT");
                table.columns[j++] = column;
            }
            cursorColumns.close();

            //connect with indexes and foreign keys
            for (Column column : table.columns) {
                column.index = indexes.get(table.name + "." + column.name);
                column.foreignKey = foreignKeys.get(table.name + "." + column.name);
            }

            //get row count
            try (Cursor cursorRowCount = database.rawQuery("SELECT COUNT(*) FROM " + table.name, null)) {
                if (cursorRowCount.moveToNext()) {
                    table.rowCount = cursorRowCount.getLong(0);
                }
            } catch (Exception e) {
                //ignore
            }

            tables.add(table);
        }
        cursor.close();
        Table[] tablesArr = new Table[tables.size()];
        tables.toArray(tablesArr);
        return tablesArr;
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
    public <T> T runTransaction(DatabaseTransactionScope<T> tx) throws Exception {
        return runTransaction(tx, new TransactionAbortHandle());
    }

    @Override
    public <T> T runTransaction(DatabaseTransactionScope<T> tx, TransactionAbortHandle abortHandle) throws Exception {
        Callable<T> task = () -> {
            //the handle interrupts this thread to wake a scope that is waiting rather than working
            abortHandle.attachWorker(Thread.currentThread());
            try {
                database.beginTransaction();
                try {
                    try {
                        //a per-scope wrapper, never shared: simpleDBOperations is the same instance
                        //every non-transactional caller uses, so the abort state can not live on it
                        T result = tx.execute(new AbortableDatabaseOperations(simpleDBOperations, abortHandle));
                        //an abort that landed after the last operation must not be committed
                        abortHandle.checkNotAborted();
                        database.setTransactionSuccessful();
                        return result;
                    } finally {
                        //the scope is over, so stop taking interrupts and clear any that arrived.
                        //endTransaction() below waits on the connection pool, and an interrupted
                        //endTransaction would leave this shared SQLiteDatabase mid-transaction for
                        //every later caller
                        abortHandle.detachWorker();
                    }
                } finally {
                    //without setTransactionSuccessful() this rolls back
                    database.endTransaction();
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

    public class SimpleDatabaseOperations implements DatabaseOperations {
        private SimpleDatabaseOperations() {

        }

        @Override
        public Table[] getTables() throws Exception {
            //one SQLiteDatabase, and its transactions are scoped to the thread that opened them,
            //so reading the schema here is already inside whatever transaction is running
            return DatabaseAndroid.this.getTables();
        }

        @Override
        public TableData query(String query, Object[] args, boolean possiblyWriteOperation) throws Exception {
            String[] argsStr = null;
            if (args != null && args.length > 0) {
                argsStr = new String[args.length];
                for (int i = 0; i < args.length; i++) {
                    argsStr[i] = args[i].toString();
                }
            }
            Cursor cursor = database.rawQuery(query, argsStr);
            if (cursor == null) {
                return null;
            }
            return new TableDataAndroid(cursor);
        }

        @Override
        public void execute(String query) throws Exception {
            database.execSQL(query);
        }

        @Override
        public long insert(String tableName, JSONObject values) throws Exception {
            return database.insertOrThrow(tableName, null, toContentValues(values));
        }

        @Override
        public int update(String tableName, JSONObject values, String[] whereFilters, Object[] whereArgs) throws Exception {
            String whereClause = null;
            String[] whereArgsStr = null;
            if (whereFilters != null && whereFilters.length > 0) {
                whereClause = DBUtils.buildSimpleWhereStatement(whereFilters);
                whereArgsStr = bindArgs(whereArgs);
            }
            return database.update(tableName, toContentValues(values), whereClause, whereArgsStr);
        }

        @Override
        public int delete(String tableName, String[] whereFilters, Object[] whereArgs) throws Exception {
            String whereClause = null;
            String[] whereArgsStr = null;
            if (whereFilters != null && whereFilters.length > 0) {
                whereClause = DBUtils.buildSimpleWhereStatement(whereFilters);
                whereArgsStr = bindArgs(whereArgs);
            }
            return database.delete(tableName, whereClause, whereArgsStr);
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

            String[] whereArgsStr = null;
            if (whereFilters != null && whereFilters.length > 0) {
                whereArgsStr = new String[whereArgs.length];
                for (int i = 0; i < whereArgs.length; i++) {
                    whereArgsStr[i] = whereArgs[i].toString();
                }
            }

            if (outCount != null) {
                String sqlString = String.format(countSql, "count(*)");

                try (Cursor cursor = database.rawQuery(sqlString, whereArgsStr)) {
                    outCount.set(cursor.moveToNext() ? cursor.getLong(0) : 0);
                }
            }

            String sqlString = String.format(sql.toString(), columnsString);
            Cursor cursor = database.rawQuery(sqlString, whereArgsStr);
            return new TableDataAndroid(cursor);
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
            //the cast changes nothing. Note this is a byte count, while the reader below walks the
            //value in the units substr() uses, which for text are characters.
            StringBuilder infoSql = new StringBuilder("SELECT typeof(")
                    .append("\"").append(column).append("\"").append("), length(CAST(")
                    .append("\"").append(column).append("\"").append(" AS BLOB)) FROM ").append(table);
            String where = null;
            if (filters != null && !filters.isEmpty()) {
                where = DBUtils.buildSimpleWhereStatement(filters.toArray(new String[0]));
                infoSql.append(" WHERE ").append(where);
            }

            infoSql.append(" LIMIT 1");
            String[] filtersArgsStr = new String[filtersArgs.size()];
            for (int i = 0; i < filtersArgs.size(); i++) {
                filtersArgsStr[i] = filtersArgs.get(i).toString();
            }
            String type;
            long length;
            try (Cursor cursor = database.rawQuery(infoSql.toString(), filtersArgsStr)) {
                if (cursor.moveToNext()) {
                    type = cursor.getString(0);
                    length = cursor.getLong(1);
                } else {
                    //no such row - the only case that means "not found"
                    return null;
                }
            }

            CellDataStreamInfo streamInfo = new CellDataStreamInfo();
            if (type == null || type.equals("null")) {
                //the row exists but the cell is empty: info without a stream, which the caller
                //answers with "no content". Returning null here would claim the row is missing,
                //which is what the desktop implementation never did.
                streamInfo.type = "null";
                return streamInfo;
            }

            if (!(type.equals("blob") || type.equals("text"))) {
                throw new IllegalArgumentException("Invalid column type: " + type);
            }

            streamInfo.type = type;
            streamInfo.length = length;
            streamInfo.mimeType = type.equals("text") ? "text/plain" : "application/octet-stream";

            final StringBuilder sql = new StringBuilder("SELECT substr(").append(column).append(", ?, ?) FROM ").append(table);
            if (where != null) {
                sql.append(" WHERE ").append(where);
            }
            sql.append(" LIMIT 1");

            final boolean isText = type.equals("text");
            streamInfo.inputStream = new InputStream() {
                /**
                 * How much of the value has been fetched, counted the way {@code substr} counts it:
                 * characters for a text value, bytes for a blob. Not the same as the number of
                 * bytes handed out, which is what UTF-8 makes of those characters.
                 */
                private long unitsFetched = 0;
                private byte[] currentPart = EMPTY_PART;
                private int currentOffset = 0;
                /** Set once a query comes back short, meaning there is nothing left to fetch. */
                private boolean exhausted = false;

                {
                    fetchNextPart();
                }

                @Override
                public int read() throws IOException {
                    if (!ensureAvailable()) {
                        return -1;
                    }
                    return currentPart[currentOffset++] & 0xFF;
                }

                @Override
                public int read(byte[] b) throws IOException {
                    return read(b, 0, b.length);
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (len == 0) {
                        return 0;
                    }
                    if (!ensureAvailable()) {
                        return -1;
                    }
                    int count = Math.min(len, currentPart.length - currentOffset);
                    System.arraycopy(currentPart, currentOffset, b, off, count);
                    currentOffset += count;
                    return count;
                }

                /** Refills from the database when the piece in hand is used up. */
                private boolean ensureAvailable() {
                    while (currentOffset >= currentPart.length) {
                        if (exhausted) {
                            return false;
                        }
                        fetchNextPart();
                    }
                    return true;
                }

                @Override
                public void close() {
                    //every query closes its own cursor, so there is nothing left open here
                }

                private void fetchNextPart() {
                    String[] arguments = new String[filtersArgs.size() + 2];
                    arguments[0] = Long.toString(unitsFetched + 1);
                    //a length, not an end offset: substr(X, Y, Z) returns Z units from Y
                    arguments[1] = Long.toString(CELL_STREAM_CHUNK_UNITS);
                    System.arraycopy(filtersArgsStr, 0, arguments, 2, filtersArgsStr.length);
                    try (Cursor cursor = database.rawQuery(sql.toString(), arguments)) {
                        long unitsInPart = 0;
                        if (cursor.moveToNext()) {
                            if (isText) {
                                //not getBlob: on a text column the cursor appends a terminating
                                //zero byte, which would corrupt the value - and, because a query
                                //past the end then returns one byte rather than none, would leave
                                //nothing to detect the end of the value by
                                String part = cursor.getString(0);
                                if (part != null) {
                                    //sqlite counts characters, so this has to count them the same
                                    //way: String.length() would count a surrogate pair twice and
                                    //skip half of the next piece
                                    unitsInPart = part.codePointCount(0, part.length());
                                    currentPart = part.getBytes(StandardCharsets.UTF_8);
                                } else {
                                    currentPart = EMPTY_PART;
                                }
                            } else {
                                byte[] part = cursor.getBlob(0);
                                currentPart = part != null ? part : EMPTY_PART;
                                unitsInPart = currentPart.length;
                            }
                        } else {
                            currentPart = EMPTY_PART;
                        }
                        currentOffset = 0;
                        unitsFetched += unitsInPart;
                        //a short answer means the value ended inside this piece
                        exhausted = unitsInPart < CELL_STREAM_CHUNK_UNITS;
                    }
                }
            };
            return streamInfo;
        }
    }

    /**
     * The bind arguments of a where clause, as the platform wants them.
     * <p>
     * One per argument, not one per filter: a single {@code IN} filter carries as many arguments as
     * it has placeholders, so sizing this by the number of filters leaves the statement with fewer
     * arguments than it has {@code ?} and the query is rejected.
     */
    private static String[] bindArgs(Object[] whereArgs) {
        if (whereArgs == null) {
            return null;
        }
        String[] args = new String[whereArgs.length];
        for (int i = 0; i < whereArgs.length; i++) {
            Object value = whereArgs[i];
            if (value == null || JSONObject.NULL.equals(value)) {
                //the platform binds where-arguments as strings and has no way to express NULL;
                //say so instead of failing later with a NullPointerException
                throw new IllegalArgumentException("Where argument #" + i + " is null, which can " +
                        "not be used in a filter - compare with IS NULL in a custom SQL query instead");
            }
            args[i] = value.toString();
        }
        return args;
    }

    private static ContentValues toContentValues(JSONObject values) {
        ContentValues contentValues = new ContentValues();
        for (Iterator<String> it = values.keys(); it.hasNext();) {
            String key = it.next();
            if (values.isNull(key)) {
                contentValues.putNull(key);
                continue;
            }
            Object value = values.opt(key);
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
            } else if (value instanceof JSONObject && ((JSONObject) value).has("type") &&
                    ((JSONObject) value).opt("type").equals("blob") &&
                    ((JSONObject) value).has("value")) {
                String base64 = ((JSONObject) value).opt("value").toString();
                contentValues.put(key, Base64.decode(base64, Base64.DEFAULT));
            } else {
                contentValues.put(key, value.toString());
            }
        }
        return contentValues;
    }
}
