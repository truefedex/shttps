package com.phlox.simpleserver.handlers.database;

import com.phlox.server.handlers.RequestHandler;
import com.phlox.server.request.RequestContext;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.utils.Holder;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;

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

    protected boolean checkIsForbidden(@Nullable User user, User.DBRights... right) {
        if (config.getAuthMode().equals(SHTTPSConfig.AuthMode.NONE)) return false;
        if (user == null) return true;
        EnumSet<User.DBRights> dbRights = authManager.getUserRightsEvaluator().userDBRights(user);
        return !dbRights.containsAll(List.of(right));
    }
}
