package com.phlox.server.handlers;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.request.RequestParser;
import com.phlox.server.responses.Response;

public interface RequestHandler {
    Response handleRequest(RequestContext context, Request request, RequestParser requestParser) throws Exception;
}
