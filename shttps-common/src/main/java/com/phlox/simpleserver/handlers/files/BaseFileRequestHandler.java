package com.phlox.simpleserver.handlers.files;

import com.phlox.server.handlers.RequestHandler;
import com.phlox.server.request.RequestContext;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;

public abstract class BaseFileRequestHandler implements RequestHandler {
    protected final SHTTPSConfig config;
    protected final AuthManager authManager;

    public BaseFileRequestHandler(@NonNull SHTTPSConfig config, @NonNull  AuthManager authManager) {
        this.config = config;
        this.authManager = authManager;
    }

    protected @Nullable User checkUser(@NonNull RequestContext context) {
        if (config.getAuthMode().equals(SHTTPSConfig.AuthMode.NONE)) return null;
        return authManager.getAuthenticatedUser(context);
    }

    protected boolean checkIsForbidden(@Nullable User user, User.FileSystemRights... right) {
        if (config.getAuthMode().equals(SHTTPSConfig.AuthMode.NONE)) return false;
        if (user == null) return true;
        return !user.fsRights.containsAll(List.of(right));
    }
}
