package com.phlox.server.request;

import com.phlox.server.responses.Response;
import com.phlox.server.utils.MultiMap;

import java.util.HashMap;

public class RequestContext {
    //Default request body reader and parser - use it if request should have a body
    public final RequestBodyReader requestBodyReader;
    //Middleware can use that to store data between request handlers/middlewares
    public final HashMap<String, Object> data = new HashMap<>();
    //used by DefaultHandlerExecutionChain to track which middleware should be executed next
    private int nextMiddlewareIndex;

    public RequestContext(RequestBodyReader requestBodyReader) {
        this.requestBodyReader = requestBodyReader;
    }

    public int nextMiddlewareIndex() {
        return nextMiddlewareIndex;
    }

    public void setNextMiddlewareIndex(int i) {
        this.nextMiddlewareIndex = i;
    }
}
