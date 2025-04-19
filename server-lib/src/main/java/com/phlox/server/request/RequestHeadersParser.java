package com.phlox.server.request;

import java.io.InputStream;

public interface RequestHeadersParser {
    Request readRequestHeaders(InputStream input, String host) throws Exception;
}
