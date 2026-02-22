package com.phlox.server.responses;

import com.phlox.server.SimpleHttpServer;
import com.phlox.server.utils.MultiMap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class Response {
    public static final String TAG = Response.class.getSimpleName();
    public static final String HEADER_SERVER = "Server";
    public static final String HEADER_CONNECTION = "Connection";
    public static final String HEADER_KEEP_ALIVE = "Keep-Alive";
    public static final String HEADER_CONTENT_TYPE = "Content-Type";
    public static final String HEADER_CONTENT_LENGTH = "Content-Length";
    public static final String HEADER_ACCEPT_RANGES = "Accept-Ranges";
    public static final String HEADER_CONTENT_RANGE = "Content-Range";
    public static final String HEADER_LAST_MODIFIED = "Last-Modified";
    public static final String HEADER_WWW_AUTHENTICATE = "WWW-Authenticate";
    public static final String HEADER_LOCATION = "Location";
    public static final String HEADER_ALLOW = "Allow";
    public static final String HEADER_CONTENT_DISPOSITION = "Content-Disposition";
    public static final String HEADER_ACCESS_CONTROL_ALLOW_ORIGIN = "Access-Control-Allow-Origin";
    public static final String HEADER_ACCESS_CONTROL_ALLOW_METHODS = "Access-Control-Allow-Methods";
    public static final String HEADER_ACCESS_CONTROL_ALLOW_HEADERS = "Access-Control-Allow-Headers";
    public static final String HEADER_ACCESS_CONTROL_ALLOW_CREDENTIALS = "Access-Control-Allow-Credentials";
    public static final String HEADER_ACCESS_CONTROL_EXPOSE_HEADERS = "Access-Control-Expose-Headers";
    public static final String HEADER_ACCESS_CONTROL_MAX_AGE = "Access-Control-Max-Age";
    public static final String HEADER_VARY = "Vary";
    public static final String HEADER_ORIGIN = "Origin";

    public int code = 200;
    public String phrase = StandardResponses.PHRASE_OK;
    private long contentLength;
    protected final InputStream stream;
    public MultiMap<String, String> headers = new MultiMap<>();
    public Object customData;

    public Response(InputStream stream) {
        this.stream = stream;
    }

    public Response() {
        this(null);
    }

    public Response(int code, String phrase, InputStream stream) {
        this(stream);
        this.code = code;
        this.phrase = phrase;
    }

    public Response(int code, String phrase) {
        this(code, phrase, null);
        setContentLength(0);
    }

    public Response(String contentType, long contentLength, InputStream stream) {
        this(stream);
        setContentType(contentType);
        setContentLength(contentLength);
    }

    public String getContentType() {
        return headers.get(HEADER_CONTENT_TYPE);
    }

    public void setContentType(String contentType) {
        headers.put(HEADER_CONTENT_TYPE, contentType);
    }

    public long getContentLength() {
        return contentLength;
    }

    public void setContentLength(long contentLength) {
        this.contentLength = contentLength;
        headers.put(HEADER_CONTENT_LENGTH, Long.toString(contentLength));
    }

    public InputStream getStream() {
        return stream;
    }

    protected String makeResponseHeader() {
        StringBuilder headersStr = new StringBuilder(SimpleHttpServer.HTTP_PROTOCOL + " " + code +
                " " + phrase + "\r\n");
        for (String key: headers.keys()) {
            List<String> values = headers.getAll(key);
            for (String value: values) {
                headersStr.append(key).append(": ").append(value).append("\r\n");
            }
        }
        return headersStr.append("\r\n").toString();
    }

    public void writeOut(OutputStream output) throws IOException {
        String header = makeResponseHeader();
        output.write(header.getBytes(StandardCharsets.UTF_8));

        try (InputStream responseStream = getStream()) {
            if (responseStream == null) return;
            byte[] buffer = new byte[1024];
            int readed;
            while ((readed = responseStream.read(buffer)) > 0) {
                output.write(buffer, 0, readed);
            }
        }
    }
}
