package com.phlox.simpleserver.database;

import com.phlox.simpleserver.database.model.Table;
import com.phlox.simpleserver.database.model.TableData;
import com.phlox.simpleserver.utils.Holder;

import org.json.JSONObject;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Wraps the {@link DatabaseOperations} of one transaction so that every call first checks the clock.
 * <p>
 * A transaction that is bounded only by how long it waits for work is not bounded at all: one
 * request can carry a batch of statements that runs for as long as it likes, holding the database's
 * single write thread the whole time. Checking here bounds the batch at exactly the granularity
 * {@link AbortableDatabaseOperations} already checks at, and costs nothing else - no scheduler, no
 * extra thread, no cancellation bookkeeping.
 * <p>
 * A single statement already executing is still not interrupted; that would need the driver's own
 * statement cancellation. The deadline takes effect before the next one starts.
 * <p>
 * Meant to be composed on top of the abortable wrapper, so a scope is subject to both checks.
 */
public class DeadlineDatabaseOperations implements DatabaseOperations {
    private final @NotNull DatabaseOperations delegate;
    private final long deadlineNanos;

    /**
     * @param deadlineNanos deadline on the {@link System#nanoTime()} clock, which unlike wall time
     *                      can not jump backwards
     */
    public DeadlineDatabaseOperations(@NotNull DatabaseOperations delegate, long deadlineNanos) {
        this.delegate = delegate;
        this.deadlineNanos = deadlineNanos;
    }

    public boolean isExpired() {
        return System.nanoTime() - deadlineNanos >= 0;
    }

    private void checkDeadline() throws DeadlineExceededException {
        if (isExpired()) {
            throw DeadlineExceededException.lifetime();
        }
    }

    @Override
    public TableData query(String query, Object[] args, boolean possiblyWriteOperation) throws Exception {
        checkDeadline();
        return delegate.query(query, args, possiblyWriteOperation);
    }

    @Override
    public void execute(String query) throws Exception {
        checkDeadline();
        delegate.execute(query);
    }

    @Override
    public long insert(String tableName, JSONObject values) throws Exception {
        checkDeadline();
        return delegate.insert(tableName, values);
    }

    @Override
    public int update(String tableName, JSONObject values, String[] whereFilters, Object[] whereArgs) throws Exception {
        checkDeadline();
        return delegate.update(tableName, values, whereFilters, whereArgs);
    }

    @Override
    public int delete(String tableName, String[] whereFilters, Object[] whereArgs) throws Exception {
        checkDeadline();
        return delegate.delete(tableName, whereFilters, whereArgs);
    }

    @Override
    public TableData getTableDataSecure(String tableName, String[] columns, Long offset, Long limit,
                                        String[] whereFilters, Object[] whereArgs, String orderBy,
                                        boolean desc, boolean includeRowId, Holder<Long> outCount) throws Exception {
        checkDeadline();
        return delegate.getTableDataSecure(tableName, columns, offset, limit, whereFilters, whereArgs,
                orderBy, desc, includeRowId, outCount);
    }

    @Override
    public Database.CellDataStreamInfo getSingleCellDataStream(String table, String column,
                                                               List<String> filters, List<Object> filtersArgs) throws Exception {
        checkDeadline();
        return delegate.getSingleCellDataStream(table, column, filters, filtersArgs);
    }

    @Override
    public Table[] getTables() throws Exception {
        checkDeadline();
        return delegate.getTables();
    }
}
