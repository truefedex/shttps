package com.phlox.simpleserver.database;

import com.phlox.simpleserver.database.model.Table;
import com.phlox.simpleserver.database.model.TableData;
import com.phlox.simpleserver.utils.Holder;

import org.json.JSONObject;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Wraps the {@link DatabaseOperations} of one transaction so that every call first asks its
 * {@link TransactionAbortHandle} whether the transaction is still wanted.
 * <p>
 * This must be created per transaction scope and never shared: the handle it carries belongs to one
 * transaction, and an implementation that handed out a shared instance would let one transaction's
 * abort break unrelated callers.
 */
public class AbortableDatabaseOperations implements DatabaseOperations {
    private final @NotNull DatabaseOperations delegate;
    private final @NotNull TransactionAbortHandle abortHandle;

    public AbortableDatabaseOperations(@NotNull DatabaseOperations delegate,
                                       @NotNull TransactionAbortHandle abortHandle) {
        this.delegate = delegate;
        this.abortHandle = abortHandle;
    }

    @Override
    public TableData query(String query, Object[] args, boolean possiblyWriteOperation) throws Exception {
        abortHandle.checkNotAborted();
        return delegate.query(query, args, possiblyWriteOperation);
    }

    @Override
    public void execute(String query) throws Exception {
        abortHandle.checkNotAborted();
        delegate.execute(query);
    }

    @Override
    public long insert(String tableName, JSONObject values) throws Exception {
        abortHandle.checkNotAborted();
        return delegate.insert(tableName, values);
    }

    @Override
    public int update(String tableName, JSONObject values, String[] whereFilters, Object[] whereArgs) throws Exception {
        abortHandle.checkNotAborted();
        return delegate.update(tableName, values, whereFilters, whereArgs);
    }

    @Override
    public int delete(String tableName, String[] whereFilters, Object[] whereArgs) throws Exception {
        abortHandle.checkNotAborted();
        return delegate.delete(tableName, whereFilters, whereArgs);
    }

    @Override
    public TableData getTableDataSecure(String tableName, String[] columns, Long offset, Long limit,
                                        String[] whereFilters, Object[] whereArgs, String orderBy,
                                        boolean desc, boolean includeRowId, Holder<Long> outCount) throws Exception {
        abortHandle.checkNotAborted();
        return delegate.getTableDataSecure(tableName, columns, offset, limit, whereFilters, whereArgs,
                orderBy, desc, includeRowId, outCount);
    }

    @Override
    public Database.CellDataStreamInfo getSingleCellDataStream(String table, String column,
                                                               List<String> filters, List<Object> filtersArgs) throws Exception {
        abortHandle.checkNotAborted();
        return delegate.getSingleCellDataStream(table, column, filters, filtersArgs);
    }

    @Override
    public Table[] getTables() throws Exception {
        abortHandle.checkNotAborted();
        return delegate.getTables();
    }
}
