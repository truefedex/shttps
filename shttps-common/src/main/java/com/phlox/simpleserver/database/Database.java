package com.phlox.simpleserver.database;

import java.io.InputStream;
import java.util.Map;

public interface Database extends AutoCloseable, DatabaseOperations {
    String getPath();
    Map<String, Object> getStatus() throws Exception;
    @Override
    void close() throws Exception;

    /**
     * Runs {@code tx} inside one transaction: committed when the scope returns, rolled back when it
     * throws. Whatever the scope throws reaches the caller unwrapped.
     * <p>
     * The scope runs on this database's single write thread, so it must not call back into this
     * {@link Database} - it gets its own {@link DatabaseOperations} and has to use that one.
     * Implementations throw {@link IllegalStateException} on such a call rather than deadlocking.
     */
    <T> T runTransaction(DatabaseTransactionScope<T> tx) throws Exception;

    /**
     * As {@link #runTransaction(DatabaseTransactionScope)}, but the transaction can be ended early
     * from another thread through {@code abortHandle} - see {@link TransactionAbortHandle} for what
     * that covers. An aborted transaction is rolled back and reported as
     * {@link TransactionAbortedException}.
     */
    <T> T runTransaction(DatabaseTransactionScope<T> tx, TransactionAbortHandle abortHandle) throws Exception;

    /**
     * This datatype is used for retrieving large string or binary data from a cell in a table.
     */
    class CellDataStreamInfo {
        public InputStream inputStream;
        public String type;
        public String mimeType;
        public long length;
    }

}
