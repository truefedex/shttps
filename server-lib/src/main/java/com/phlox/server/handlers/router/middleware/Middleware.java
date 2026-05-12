package com.phlox.server.handlers.router.middleware;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;

@FunctionalInterface
public interface Middleware {
    Response handle(RequestContext context, Request request, HandlerExecutionChain chain) throws Exception;
}
