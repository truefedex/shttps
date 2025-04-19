package com.phlox.server.handlers;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestBodyReader;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;

public interface RequestHandler {
    Response handleRequest(RequestContext context, Request request, RequestBodyReader requestBodyReader) throws Exception;

    /**
     * Check if this handler can handle the request.
     * Can be used by @{@link RoutingRequestHandler} to find the correct handler.
     */
    default boolean canHandle(String path, String method) {
        return true;
    }
}
