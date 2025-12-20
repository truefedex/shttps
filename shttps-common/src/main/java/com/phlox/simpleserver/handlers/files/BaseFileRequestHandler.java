package com.phlox.simpleserver.handlers.files;

import com.phlox.server.handlers.RequestHandler;
import com.phlox.server.request.RequestContext;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.auth.UserStore;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

public abstract class BaseFileRequestHandler implements RequestHandler {
    protected final SHTTPSConfig config;
    protected final AuthManager authManager;
    protected final UserStore userStore;

    public BaseFileRequestHandler(@NonNull SHTTPSConfig config, @NonNull  AuthManager authManager,
                                  @NonNull UserStore userStore) {
        this.config = config;
        this.authManager = authManager;
        this.userStore = userStore;
    }

    protected @Nullable User checkUser(@NonNull RequestContext context) {
        if (config.getAuthMode().equals(SHTTPSConfig.AuthMode.NONE)) return null;
        return authManager.getAuthenticatedUser(context);
    }

    protected boolean checkIsForbidden(@Nullable User user, @NonNull String subject,
                @NonNull String operation, @Nullable Map<String, Object> operationParams,
                                       User.FileSystemRights... requestedRights) {
        if (config.getAuthMode().equals(SHTTPSConfig.AuthMode.NONE)) return false;
        if (user == null) return true;
        try {
            return !authManager.getUserRightsEvaluator().checkIsFileOperationAllowed(user, config.isStoreUsersInDatabase(),
                    subject, operation, operationParams, requestedRights);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


}
