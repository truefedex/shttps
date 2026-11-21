package com.phlox.server.request;

import com.phlox.server.utils.MultiMap;
import com.phlox.server.utils.ScannerInputStream;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Request {
    public static final String METHOD_GET = "GET";
    public static final String METHOD_POST = "POST";
    public static final String METHOD_HEAD = "HEAD";
    public static final String METHOD_PUT = "PUT";
    public static final String METHOD_DELETE = "DELETE";
    public static final String METHOD_OPTIONS = "OPTIONS";
    public static final String METHOD_TRACE = "TRACE";
    public static final String METHOD_CONNECT = "CONNECT";
    public static final String METHOD_PATCH = "PATCH";
    public static final String METHOD_PROP_FIND = "PROPFIND";
    public static final String METHOD_PROP_PATCH = "PROPPATCH";
    public static final String METHOD_MKCOL = "MKCOL";
    public static final String METHOD_COPY = "COPY";
    public static final String METHOD_MOVE = "MOVE";
    public static final String METHOD_LOCK = "LOCK";
    public static final String METHOD_UNLOCK = "UNLOCK";

    public static final String HEADER_IF_MODIFIED_SINCE = "if-modified-since";
    public static final String HEADER_AUTHORIZATION = "authorization";
    public static final String HEADER_RANGE = "range";
    public static final String HEADER_CONTENT_TYPE = "content-type";
    public static final String HEADER_CONTENT_DISPOSITION = "content-disposition";
    public static final String HEADER_CONTENT_LENGTH = "content-length";
    public static final String HEADER_TRANSFER_ENCODING =  "transfer-encoding";
    public static final String HEADER_COOKIE = "cookie";
    public static final String HEADER_HOST  =  "host";
    public static final String HEADER_CONNECTION  =  "connection";
    public static final String HEADER_USER_AGENT  =  "user-agent";
    public static final String HEADER_ACCEPT  =  "accept";
    public static final String HEADER_ORIGIN = "origin";
    public static final String HEADER_ACCESS_CONTROL_REQUEST_METHOD = "access-control-request-method";
    public static final String HEADER_ACCESS_CONTROL_REQUEST_HEADERS = "access-control-request-headers";
    public static final String HEADER_EXPECT = "expect";

    public static final String EXPECTATION_100_CONTINUE = "100-continue";

    public static final String CONTENT_TYPE_MULTIPART_FORM = "multipart/form-data";
    public static final String CONTENT_TYPE_URL_ENCODED_FORM = "application/x-www-form-urlencoded";

    public static final String TRANSFER_ENCODING_CHUNKED  =  "chunked";
    public static final String CONNECTION_CLOSE = "close";
    private static final List<String> requestTypesThatTypicallyShouldHaveABody = List.of(
            "POST", "PUT", "PATCH", "PROPFIND", "PROPPATCH", "SEARCH", "LOCK", "ORDERPATCH"
    );

    public String method;
    public String rawPathAndQuery;
    public String path;
    public MultiMap<String, String> queryParams = new MultiMap<>();
    public Map<String, String> cookies = new HashMap<>();
    public MultiMap<String, String> urlEncodedPostParams = new MultiMap<>();
    public String hostAddress;
    public MultiMap<String, String> headers = new MultiMap<>();
    public ScannerInputStream input;
    //framed view of the request body: exactly the body octets, EOF at the end.
    //All body consumption must go through this stream, not through 'input' directly
    public BodyInputStream bodyStream;
    public String contentType;
    public String boundary;
    public String charset;
    public long contentLength = -1;
    //client sent "Expect: 100-continue" and waits for an interim response before sending the body
    public boolean expectContinue = false;
    public boolean requestToCloseConnection = true;
    public RequestBody body;
    public Set<FormDataPart> multipartData = new HashSet<>();
    public long time = System.currentTimeMillis();
    public long connectionId;

    public boolean shouldHaveABody() {
        return requestTypesThatTypicallyShouldHaveABody.contains(method);
    }
}
