package com.phlox.simpleserver.handlers.files;

import com.phlox.server.handlers.RequestHandler;
import com.phlox.server.request.RequestContext;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.auth.UserStore;
import com.phlox.simpleserver.handlers.files.webdav.LockManager;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

public abstract class BaseFileRequestHandler implements RequestHandler {
    protected final SHTTPSConfig config;
    protected final AuthManager authManager;
    protected final UserStore userStore;
    protected final LockManager locks;

    public BaseFileRequestHandler(@NotNull SHTTPSConfig config, @NotNull  AuthManager authManager,
                                  @NotNull UserStore userStore, @NotNull LockManager locks) {
        this.config = config;
        this.authManager = authManager;
        this.userStore = userStore;
        this.locks = locks;
    }

    protected @Nullable User checkUser(@NotNull RequestContext context) {
        if (config.getAuthMode().equals(SHTTPSConfig.AuthMode.NONE)) return null;
        return authManager.getAuthenticatedUser(context);
    }

    public boolean checkIsForbidden(@Nullable User user, @NotNull String subject,
                @NotNull String operation, @Nullable Map<String, Object> operationParams,
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

    /**
     * {@link #checkIsForbidden} for an operation that acts on everything under {@code subject}:
     * also forbidden when an access rule denies the operation on any folder inside it. See
     * {@link com.phlox.simpleserver.auth.UserRightsEvaluator#findDeniedDescendants}.
     */
    public boolean checkIsSubtreeForbidden(@Nullable User user, @NotNull String subject,
                                           @NotNull String operation, @Nullable Map<String, Object> operationParams,
                                           User.FileSystemRights... requestedRights) {
        if (checkIsForbidden(user, subject, operation, operationParams, requestedRights)) return true;
        return !deniedDescendants(user, subject, operation, operationParams).isEmpty();
    }

    /** Folders inside {@code subject} where an access rule denies {@code operation}; normalized paths. */
    public @NotNull List<String> deniedDescendants(@Nullable User user, @NotNull String subject,
                                                   @NotNull String operation, @Nullable Map<String, Object> operationParams) {
        if (config.getAuthMode().equals(SHTTPSConfig.AuthMode.NONE) || user == null) return new ArrayList<>();
        try {
            return authManager.getUserRightsEvaluator().findDeniedDescendants(user, config.isStoreUsersInDatabase(),
                    subject, operation, operationParams);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


}
