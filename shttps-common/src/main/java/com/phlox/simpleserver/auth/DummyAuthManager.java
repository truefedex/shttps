package com.phlox.simpleserver.auth;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class DummyAuthManager implements AuthManager {
    @Override
    public @Nullable User getAuthenticatedUser(@NonNull RequestContext context) {
        return null;
    }

    @Override
    public User authenticate(RequestContext context, Request request) {
        return null;
    }

    @Override
    public void logout(@NonNull RequestContext context, @NonNull Request request) {

    }

    @Override
    public @NonNull UserRightsEvaluator getUserRightsEvaluator() {
        throw new IllegalStateException("Method getUserRightsEvaluator is undefined for DummyAuthManager");
    }
}
