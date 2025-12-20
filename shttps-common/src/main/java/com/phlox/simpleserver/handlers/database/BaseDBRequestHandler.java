package com.phlox.simpleserver.handlers.database;

import com.phlox.server.handlers.RequestHandler;
import com.phlox.server.request.RequestContext;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.DBAccessRule;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.DatabaseOperations;
import com.phlox.simpleserver.utils.Holder;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

public abstract class BaseDBRequestHandler implements RequestHandler {
    protected final Holder<Database> database;
    protected final SHTTPSConfig config;
    protected final AuthManager authManager;

    public BaseDBRequestHandler(Holder<Database> database, SHTTPSConfig config, @NonNull AuthManager authManager) {
        this.database = database;
        this.config = config;
        this.authManager = authManager;
    }

    protected @Nullable User checkUser(@NonNull RequestContext context) {
        if (config.getAuthMode().equals(SHTTPSConfig.AuthMode.NONE)) return null;
        return authManager.getAuthenticatedUser(context);
    }

    protected boolean checkIsForbidden(@NonNull DatabaseOperations db, @Nullable User user, @NonNull String subject,
                                       @NonNull String operation, @Nullable Map<String, Object> operationParams,
                                       User.DBRights... requestedRights) {
        if (config.getAuthMode().equals(SHTTPSConfig.AuthMode.NONE)) return false;
        if (user == null) return true;
        return !authManager.getUserRightsEvaluator().checkIsDBOperationAllowed(db, user, config.isStoreUsersInDatabase(), subject,
                operation, operationParams, requestedRights);
    }
}
