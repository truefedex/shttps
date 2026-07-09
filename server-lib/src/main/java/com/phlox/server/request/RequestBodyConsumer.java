package com.phlox.server.request;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;

public interface RequestBodyConsumer {
    OutputStream prepareBinaryOutputForMultipartData(Request request, String contentType, String name, String fileName, Map<String, String> partHeaders) throws Exception;

    OutputStream prepareBinaryOutputForRequestBodyData(Request request) throws Exception;
}
