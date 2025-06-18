package com.phlox.simpleserver.auth.web;

import com.phlox.server.handlers.Middleware;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;

public class WebAuthMiddleware implements Middleware {

    private final AuthManager authManager;
    private final String loginRedirectPath;

    public WebAuthMiddleware(AuthManager authManager, String loginRedirectPath) {
        this.authManager = authManager;
        this.loginRedirectPath = loginRedirectPath;
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        User user = authManager.authenticate(context, request);
        if (user == null) {
            if (request.method.equals(Request.METHOD_GET) &&
                    (request.contentType == null || "text/html".equals(request.contentType))) {
                return StandardResponses.REDIRECT(loginRedirectPath, "Found", 302);
            } else {
                return StandardResponses.UNAUTHORIZED();
            }
        }
        return null;
    }
}
