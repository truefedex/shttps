package com.phlox.simpleserver.auth.basic;

import com.phlox.server.handlers.router.middleware.HandlerExecutionChain;
import com.phlox.server.handlers.router.middleware.Middleware;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;

public class BasicAuthMiddleware implements Middleware {

    private final AuthManager authManager;

    public BasicAuthMiddleware(AuthManager authManager) {
        this.authManager = authManager;
    }

    @Override
    public Response handle(RequestContext context, Request request, HandlerExecutionChain chain) throws Exception {
        User user = authManager.authenticate(context, request);
        if (user == null) {
            Response response = StandardResponses.UNAUTHORIZED();
            response.headers.put(Response.HEADER_WWW_AUTHENTICATE, "Basic realm=\"Authentication required\", charset=\"UTF-8\"");
            return response;
        }
        return chain.proceed(context, request);
    }
}
