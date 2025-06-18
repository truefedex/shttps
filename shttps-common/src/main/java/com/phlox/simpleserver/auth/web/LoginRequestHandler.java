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

public class LoginRequestHandler implements RequestHandler {
    private final WebAuthManager authManager;
    private final SessionManager sessionManager;

    public LoginRequestHandler(AuthManager authManager, SessionManager sessionManager) {
        this.authManager = (WebAuthManager) authManager;
        this.sessionManager = sessionManager;
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        if (!request.method.equals(Request.METHOD_POST)) return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_POST});
        if (!Request.CONTENT_TYPE_URL_ENCODED_FORM.equals(request.contentType)) {
            return StandardResponses.BAD_REQUEST();
        }
        context.requestBodyReader.readRequestBody(request);
        User user = authManager.login(context, request);
        if (user != null) {
            String sessionId = sessionManager.createSession(user.identity);
            Response response = new Response();
            Map<String, Object> options = new HashMap<>();
            options.put("Path", "/");
            //options.put("Domain", "example.com");
            //options.put("Secure", true);
            options.put("HttpOnly", true);
            options.put("SameSite", "Lax");
            response.headers.put("Set-Cookie",
                    HTTPUtils.buildSetCookieHeader(
                            WebAuthManager.COOKIE_KEY_SESSION_ID, sessionId, options
                    ));
            return response;
        } else {
            return StandardResponses.UNAUTHORIZED();
        }
    }
}
