package com.phlox.server.request;

import com.phlox.server.responses.Response;
import com.phlox.server.utils.MultiMap;

import java.util.HashMap;

public class RequestContext {
    //Default request body reader and parser - use it if request should have a body
    public final RequestBodyReader requestBodyReader;
    //Middleware can use that to store data between request handlers/middlewares
    public final HashMap<String, Object> data = new HashMap<>();
    //There can be stored additional headers for the response added by middlewares
    public final MultiMap<String, String> additionalResponseHeaders = new MultiMap<>();
    //There could be stored RequestHandler's response to use by post-middleware
    public Response response;

    public RequestContext(RequestBodyReader requestBodyReader) {
        this.requestBodyReader = requestBodyReader;
    }
}
