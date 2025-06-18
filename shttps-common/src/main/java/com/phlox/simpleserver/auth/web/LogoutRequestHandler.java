package com.phlox.simpleserver.auth.web;

import com.phlox.server.handlers.RequestHandler;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.HTTPUtils;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;

import java.util.HashMap;
import java.util.Map;

public class LogoutRequestHandler implements RequestHandler {
    private AuthManager authManager;

    public LogoutRequestHandler(AuthManager authManager) {
        this.authManager = authManager;
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        if (!request.method.equals(Request.METHOD_POST)) return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_POST});
        authManager.logout(context, request);
        Response response = StandardResponses.NO_CONTENT();
        Map<String, Object> options = new HashMap<>();
        options.put("Path", "/");
        options.put("Expires ", -1);
        //options.put("Domain", "example.com");
        //options.put("Secure", true);
        options.put("HttpOnly", true);
        options.put("SameSite", "Lax");
        response.headers.put("Set-Cookie",
                HTTPUtils.buildSetCookieHeader(
                        WebAuthManager.COOKIE_KEY_SESSION_ID, "", options
                ));
        return response;
    }
}
