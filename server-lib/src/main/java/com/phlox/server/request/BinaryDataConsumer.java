package com.phlox.server.request;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;

public interface BinaryDataConsumer {
    OutputStream prepareBinaryOutputForMultipartData(Request request, String contentType, String name, String fileName, Map<String, String> partHeaders) throws IOException;

    OutputStream prepareBinaryOutputForRequestBodyData(Request request) throws IOException;
}
