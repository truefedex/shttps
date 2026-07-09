package com.phlox.simpleserver.auth;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface AuthManager {
    @Nullable
    User getAuthenticatedUser(@NotNull RequestContext context);
    @Nullable User authenticate(RequestContext context, Request request);
    void logout(@NotNull RequestContext context, @NotNull Request request);
    @NotNull UserRightsEvaluator getUserRightsEvaluator();
}
