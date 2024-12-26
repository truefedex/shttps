package com.phlox.server.request;

import java.io.IOException;
import java.io.InputStream;

public interface RequestParser {
    Request parseRequestHeaders(InputStream input, String host) throws Exception;
    void parseRequestBody(Request request, BinaryDataConsumer customBinaryDataConsumer) throws Exception;
    default void parseRequestBody(Request request) throws Exception {
        parseRequestBody(request, null);
    }
}
