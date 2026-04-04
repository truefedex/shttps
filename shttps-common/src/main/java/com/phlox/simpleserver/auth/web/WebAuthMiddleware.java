package com.phlox.simpleserver.auth.web;

import com.phlox.server.handlers.Middleware;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.HTTPUtils;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;

import java.util.Map;

public class WebAuthMiddleware implements Middleware {

    private final AuthManager authManager;
    private final String loginRedirectPath;
    private final boolean registrationAllowed;

    public WebAuthMiddleware(AuthManager authManager, String loginRedirectPath, boolean registrationAllowed) {
        this.authManager = authManager;
        this.loginRedirectPath = loginRedirectPath;
        this.registrationAllowed = registrationAllowed;
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        User user = authManager.authenticate(context, request);
        if (user == null) {
            if (request.path != null && request.path.startsWith(loginRedirectPath)) {
                addRegAllowedHeader(context);
                return null; // Allow access to login page and related resources
            }
            if (request.method.equals(Request.METHOD_GET) &&
                    (request.contentType == null || "text/html".equals(request.contentType))) {
                addRegAllowedHeader(context);
                return StandardResponses.REDIRECT(loginRedirectPath, "Found", 302);
            } else {
                return StandardResponses.UNAUTHORIZED();
            }
        }
        return null;
    }

    private void addRegAllowedHeader(RequestContext context) {
        //set cookie to indicate that registration is allowed
        Map<String, Object> options = Map.of(
                "Path", "/",
                "HttpOnly", false,
                "SameSite", "Lax",
                "Max-Age", 60
        );
        context.additionalResponseHeaders.put("Set-Cookie",
                HTTPUtils.buildSetCookieHeader("registration_allowed",
                        registrationAllowed ? "1" : "0", options));
    }
}
