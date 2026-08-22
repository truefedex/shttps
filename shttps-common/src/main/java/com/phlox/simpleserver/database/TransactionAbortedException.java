package com.phlox.simpleserver.database;

import org.jetbrains.annotations.Nullable;

/**
 * Thrown out of {@link Database#runTransaction(DatabaseTransactionScope, TransactionAbortHandle)}
 * when the scope was ended by {@link TransactionAbortHandle#abort}. The transaction is rolled back
 * before this reaches the caller.
 */
public class TransactionAbortedException extends Exception {
    public TransactionAbortedException(@Nullable String reason) {
        super(reason == null ? "Transaction aborted" : "Transaction aborted: " + reason);
    }

    public TransactionAbortedException(@Nullable String reason, @Nullable Throwable cause) {
        super(reason == null ? "Transaction aborted" : "Transaction aborted: " + reason, cause);
    }
}
