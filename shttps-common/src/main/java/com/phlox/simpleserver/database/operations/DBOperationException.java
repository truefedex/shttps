package com.phlox.simpleserver.database.operations;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * How a {@link DBOperation} refuses or fails.
 * <p>
 * The operations know nothing about HTTP, so they can not answer with a status code; they say what
 * kind of thing went wrong and let each transport phrase it. {@code DBRequestHandlers} map the kind
 * onto {@code StandardResponses}, and a future WebSocket transport maps it onto whatever its own
 * error frame looks like.
 * <p>
 * Being an exception rather than a return value is deliberate: everything an operation does happens
 * inside {@link com.phlox.simpleserver.database.Database#runTransaction}, which commits on a normal
 * return and rolls back on a throw. A failure that came back as a value would be committed.
 */
public class DBOperationException extends Exception {
    public enum Kind {
        /** The request itself is malformed - missing parameter, unparseable JSON, bad number. */
        BAD_REQUEST,
        /** The user may not do this. */
        FORBIDDEN,
        /** No such table, row or cell. */
        NOT_FOUND,
        /** The feature is switched off in the server configuration. */
        DISABLED,
        /** The database refused the operation - constraint violation, SQL syntax error. */
        FAILED
    }

    public final @NotNull Kind kind;

    public DBOperationException(@NotNull Kind kind, @Nullable String message) {
        super(message);
        this.kind = kind;
    }

    public DBOperationException(@NotNull Kind kind, @Nullable String message, @Nullable Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public static DBOperationException badRequest(String message) {
        return new DBOperationException(Kind.BAD_REQUEST, message);
    }

    public static DBOperationException forbidden() {
        return new DBOperationException(Kind.FORBIDDEN, "Forbidden");
    }

    public static DBOperationException notFound(String message) {
        return new DBOperationException(Kind.NOT_FOUND, message);
    }

    public static DBOperationException disabled(String message) {
        return new DBOperationException(Kind.DISABLED, message);
    }

    public static DBOperationException failed(String message, Throwable cause) {
        return new DBOperationException(Kind.FAILED, message, cause);
    }
}
