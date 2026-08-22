package com.phlox.simpleserver.database;

import org.jetbrains.annotations.NotNull;

/**
 * Thrown when a transaction ran past a deadline it was given. Leaving the transaction scope, it
 * reaches the implementation's rollback path like any other failure.
 */
public class DeadlineExceededException extends Exception {
    public enum Kind {
        /** The transaction as a whole has lived as long as it is allowed to. */
        LIFETIME,
        /** Nothing was asked of the transaction for too long. */
        INACTIVITY
    }

    public final @NotNull Kind kind;

    public DeadlineExceededException(@NotNull Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public static DeadlineExceededException lifetime() {
        return new DeadlineExceededException(Kind.LIFETIME,
                "Transaction exceeded its maximum lifetime");
    }

    public static DeadlineExceededException inactivity() {
        return new DeadlineExceededException(Kind.INACTIVITY,
                "Transaction was inactive for too long");
    }
}
