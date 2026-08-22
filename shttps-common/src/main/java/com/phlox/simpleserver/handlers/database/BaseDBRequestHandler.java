package com.phlox.simpleserver.handlers.database;

import com.phlox.server.handlers.RequestHandler;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.MultiMap;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.operations.DBOperation;
import com.phlox.simpleserver.database.operations.DBOperationException;
import com.phlox.simpleserver.utils.Holder;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The HTTP half of a database endpoint: it reads parameters off the request, hands them to a
 * {@link DBOperation}, and turns what comes back into a response. Everything else - what the
 * parameters mean, whether the feature is enabled, whether this user may do it - belongs to the
 * operation, so that other transports can offer the same thing without copying any of it.
 */
public abstract class BaseDBRequestHandler implements RequestHandler {
    protected final Holder<Database> database;
    protected final SHTTPSConfig config;
    protected final AuthManager authManager;

    public BaseDBRequestHandler(Holder<Database> database, SHTTPSConfig config, @NotNull AuthManager authManager) {
        this.database = database;
        this.config = config;
        this.authManager = authManager;
    }

    protected @Nullable User checkUser(@NotNull RequestContext context) {
        if (config.getAuthMode().equals(SHTTPSConfig.AuthMode.NONE)) return null;
        return authManager.getAuthenticatedUser(context);
    }

    /**
     * The parameters of a request that takes them either in the query string (GET) or in a
     * form-encoded body (POST).
     */
    protected MultiMap<String, String> readParams(RequestContext context, Request request) throws Exception {
        if (request.method.equals(Request.METHOD_GET)) {
            return request.queryParams;
        }
        context.requestBodyReader.readRequestBody(request);
        return request.urlEncodedPostParams;
    }

    /**
     * Runs {@code operation} in a transaction of the current database.
     *
     * @throws DBOperationException if the operation refuses or fails - use {@link #toResponse} to
     *         turn it into an answer. Exceptions are deliberately left to escape the transaction
     *         scope: catching one inside it would let the transaction commit the half-done work.
     */
    protected <R> R runInTransaction(@NotNull Database database, @NotNull DBOperation<R> operation,
                                     @Nullable User user) throws Exception {
        return database.runTransaction(db -> operation.execute(db, user));
    }

    /** The database, or null if none is open - in which case the caller answers 404. */
    protected @Nullable Database currentDatabase() {
        return this.database.get();
    }

    /**
     * Maps a refusal onto the status code that says the same thing in HTTP.
     */
    protected static Response toResponse(@NotNull DBOperationException e) {
        String message = e.getMessage();
        switch (e.kind) {
            case BAD_REQUEST:
                return StandardResponses.BAD_REQUEST(message);
            case FORBIDDEN:
                return message == null ? StandardResponses.FORBIDDEN() : StandardResponses.FORBIDDEN(message);
            case DISABLED:
                //the feature exists but is switched off; refusing is the same answer as before
                return StandardResponses.FORBIDDEN(message);
            case NOT_FOUND:
                return message == null ? StandardResponses.NOT_FOUND() : StandardResponses.NOT_FOUND(message);
            case FAILED:
            default:
                return StandardResponses.INTERNAL_SERVER_ERROR(message);
        }
    }

    /**
     * Turns anything a database operation threw into a response: refusals keep their meaning,
     * a bad table or column name is the client's mistake, and the rest is a server error.
     */
    protected static Response toResponse(@NotNull Exception e, @NotNull String failureMessagePrefix) {
        if (e instanceof DBOperationException) {
            return toResponse((DBOperationException) e);
        }
        if (e instanceof SecurityException) {
            //DBUtils rejects identifiers that are not valid table or column names
            return StandardResponses.FORBIDDEN(e.getMessage());
        }
        if (e instanceof IllegalArgumentException) {
            return StandardResponses.BAD_REQUEST(e.getMessage());
        }
        return StandardResponses.INTERNAL_SERVER_ERROR(failureMessagePrefix + e.getMessage());
    }
}
