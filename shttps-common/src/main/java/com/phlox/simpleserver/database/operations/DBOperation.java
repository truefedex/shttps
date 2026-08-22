package com.phlox.simpleserver.database.operations;

import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.database.DatabaseOperations;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * One database operation, with everything a transport does not need to know about: what the request
 * parameters mean, whether the feature is switched on, and whether this user may do it.
 * <p>
 * The point of the split is that the permission checks live here, in one place, instead of being
 * re-implemented by every transport that wants to offer the same operations. An HTTP handler turns
 * a {@code Request} into the parameters of one of these and turns the result into a {@code
 * Response}; anything else that speaks to clients does the same with its own message format, and
 * gets identical enforcement for free.
 * <p>
 * Implementations receive a {@link DatabaseOperations}, never a
 * {@link com.phlox.simpleserver.database.Database}: they always run inside a transaction scope, and
 * the outer Database object is exactly what must not be touched from there.
 *
 * @param <R> what the operation produces. Cursor-shaped results ({@link TableDataResult}, a
 *            {@code CellDataStreamInfo}) are handed over open, so the caller can decide between
 *            streaming them out and reading them in full - see
 *            {@link com.phlox.simpleserver.database.TableDataSerializer}.
 */
public abstract class DBOperation<R> {
    protected final @NotNull SHTTPSConfig config;
    protected final @NotNull AuthManager authManager;

    protected DBOperation(@NotNull SHTTPSConfig config, @NotNull AuthManager authManager) {
        this.config = config;
        this.authManager = authManager;
    }

    /**
     * Runs the operation, having first checked the configuration gate and the user's rights.
     *
     * @param db   the operations of the enclosing transaction
     * @param user the authenticated user, or null when authentication is switched off
     * @throws DBOperationException when the operation is refused; the kind says why
     */
    public abstract R execute(@NotNull DatabaseOperations db, @Nullable User user) throws Exception;

    /**
     * Refuses unless this user may perform {@code operation} on {@code subject}.
     * <p>
     * With authentication off there are no users and nothing to check. With it on, a request that
     * got this far without a user is refused: the auth middleware should have stopped it, and
     * guessing in its favour here would be the wrong way to be wrong.
     */
    protected void checkAllowed(@NotNull DatabaseOperations db, @Nullable User user,
                                @NotNull String subject, @NotNull String operation,
                                @Nullable Map<String, Object> operationParams,
                                User.DBRights... requestedRights) throws DBOperationException {
        if (config.getAuthMode().equals(SHTTPSConfig.AuthMode.NONE)) {
            return;
        }
        if (user == null) {
            throw DBOperationException.forbidden();
        }
        boolean allowed = authManager.getUserRightsEvaluator().checkIsDBOperationAllowed(db, user,
                config.isStoreUsersInDatabase(), subject, operation, operationParams, requestedRights);
        if (!allowed) {
            throw DBOperationException.forbidden();
        }
    }

    /**
     * Refuses unless the table data editing API is switched on.
     * <p>
     * Static so that a transport can also ask before it goes to the trouble of decoding a request
     * body - a switched-off feature should answer the same way whether or not the request that
     * reached it was well formed.
     */
    public static void checkTableDataEditingEnabled(@NotNull SHTTPSConfig config) throws DBOperationException {
        if (!config.isAllowDatabaseTableDataEditingApi()) {
            throw DBOperationException.disabled("Database table data editing API is disabled");
        }
    }

    /** Refuses unless the custom SQL API is switched on. See {@link #checkTableDataEditingEnabled}. */
    public static void checkCustomSqlEnabled(@NotNull SHTTPSConfig config) throws DBOperationException {
        if (!config.isAllowDatabaseCustomSqlRemoteApi()) {
            throw DBOperationException.disabled("Database custom SQL remote API is disabled");
        }
    }

    protected void checkTableDataEditingEnabled() throws DBOperationException {
        checkTableDataEditingEnabled(config);
    }

    protected void checkCustomSqlEnabled() throws DBOperationException {
        checkCustomSqlEnabled(config);
    }

    protected static @NotNull String requireTable(@Nullable String table) throws DBOperationException {
        if (table == null) {
            throw DBOperationException.badRequest("table parameter is required");
        }
        return table;
    }
}
