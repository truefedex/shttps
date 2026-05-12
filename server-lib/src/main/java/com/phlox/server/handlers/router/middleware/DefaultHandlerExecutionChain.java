package com.phlox.server.handlers.router.middleware;

import com.phlox.server.handlers.RequestHandler;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;

public final class DefaultHandlerExecutionChain implements HandlerExecutionChain {

    private final Middleware[] middlewares;
    private final RequestHandler handler;

    public DefaultHandlerExecutionChain(Middleware[] middlewares, RequestHandler handler) {
        this.middlewares = middlewares;
        this.handler = handler;
    }

    @Override
    public Response proceed(RequestContext ctx, Request req) throws Exception {
        int index = ctx.nextMiddlewareIndex();

        if (index < middlewares.length) {
            ctx.setNextMiddlewareIndex(index + 1);
            return middlewares[index].handle(ctx, req, this);
        }

        return handler.handleRequest(ctx, req);
    }
}
