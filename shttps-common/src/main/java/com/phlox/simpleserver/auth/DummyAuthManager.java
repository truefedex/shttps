package com.phlox.simpleserver.auth;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class DummyAuthManager implements AuthManager {
    @Override
    public @Nullable User getAuthenticatedUser(@NotNull RequestContext context) {
        return null;
    }

    @Override
    public User authenticate(RequestContext context, Request request) {
        return null;
    }

    @Override
    public void logout(@NotNull RequestContext context, @NotNull Request request) {

    }

    @Override
    public @NotNull UserRightsEvaluator getUserRightsEvaluator() {
        throw new IllegalStateException("Method getUserRightsEvaluator is undefined for DummyAuthManager");
    }
}
