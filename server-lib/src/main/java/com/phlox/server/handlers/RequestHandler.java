package com.phlox.server.handlers;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestBodyReader;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;

@FunctionalInterface
public interface RequestHandler {
    Response handleRequest(RequestContext context, Request request) throws Exception;
}
