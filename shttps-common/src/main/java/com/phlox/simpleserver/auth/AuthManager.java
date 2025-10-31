package com.phlox.simpleserver.auth;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface AuthManager {
    @Nullable
    User getAuthenticatedUser(@NonNull RequestContext context);
    @Nullable User authenticate(RequestContext context, Request request);
    void logout(@NonNull RequestContext context, @NonNull Request request);
    @NonNull UserRightsEvaluator getUserRightsEvaluator();
}
