package com.phlox.server.handlers.router.middleware.impl;

import com.phlox.server.handlers.router.middleware.HandlerExecutionChain;
import com.phlox.server.handlers.router.middleware.Middleware;
import com.phlox.server.platform.Base64;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;

import java.util.concurrent.atomic.AtomicLong;

public class BasicAuthMiddleware implements Middleware {
    public static final String UNAUTHORIZED = "Unauthorized";
    public static final String CHARSET_NAME = "UTF-8";
    public static final String CONTEXT_KEY_BASIC_AUTH_USERNAME = "basic_auth_user";

    public volatile String username = "";
    public volatile String password = "";
    public volatile boolean authEnabled = true;
    public final String realm = "Authentication required";
    public volatile long maxAttempts = 10L;
    private final AtomicLong currentAttempts = new AtomicLong(0);

    public BasicAuthMiddleware() {
    }

    public BasicAuthMiddleware(String username, String password) {
        this();
        this.username = username;
        this.password = password;
    }

    @Override
    public Response handle(RequestContext context, Request request, HandlerExecutionChain chain) throws Exception {
        if (!authEnabled) {
            return chain.proceed(context, request);
        }
        String authorization = request.headers.get(Request.HEADER_AUTHORIZATION);
        if ((maxAttempts == -1 || currentAttempts.get() < maxAttempts) &&
                authorization != null && authorization.toLowerCase().startsWith("basic")) {
            String base64Credentials = authorization.substring("Basic".length()).trim();
            byte[] credDecoded = Base64.decode(base64Credentials);
            String credentials = new String(credDecoded, CHARSET_NAME);
            // credentials = username:password
            final String[] values = credentials.split(":", 2);
            if (values.length == 2 && values[0].equals(username) && values[1].equals(password)) {
                currentAttempts.set(0);
                context.data.put(CONTEXT_KEY_BASIC_AUTH_USERNAME, username);
                return chain.proceed(context, request);
            } else {
                synchronized (this) {
                    //wait for 3 seconds to prevent brute force attacks
                    Thread.sleep(3000);
                }
                currentAttempts.incrementAndGet();
            }
        }

        Response response = new Response(401, UNAUTHORIZED);
        response.headers.put(Response.HEADER_WWW_AUTHENTICATE, "Basic realm=\"" + realm + "\", charset=\"UTF-8\"");
        return response;
    }
}
