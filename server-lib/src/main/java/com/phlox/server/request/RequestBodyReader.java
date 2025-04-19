package com.phlox.server.request;

public interface RequestBodyReader {
    void readRequestBody(Request request, BinaryDataConsumer customBinaryDataConsumer) throws Exception;

    default void readRequestBody(Request request) throws Exception {
        readRequestBody(request, null);
    }
}
