package com.phlox.server.responses;

import com.phlox.server.SimpleHttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

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

    public int code = 200;
    public String phrase = "OK";
    private long contentLength;
    protected final InputStream stream;
    public Map<String, String> headers = new HashMap<>();
    public Object customData;

    public Response(InputStream stream) {
        this.stream = stream;
        addDefaultHeaders();
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

    private void addDefaultHeaders() {
        headers.put(HEADER_SERVER, SimpleHttpServer.class.getSimpleName());
        headers.put(HEADER_CONNECTION, "Keep-Alive");
        headers.put(HEADER_KEEP_ALIVE, "timeout=5, max=75");
    }

    public InputStream getStream() {
        return stream;
    }

    protected String makeResponseHeader() {
        StringBuilder headersStr = new StringBuilder("HTTP/1.1 " + code + " " + phrase + "\n");
        for (Map.Entry<String, String> entry: headers.entrySet()) {
            headersStr.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        return headersStr.append("\n").toString();
    }

    public void writeOut(OutputStream output) throws IOException {
        String header = makeResponseHeader();
        output.write(header.getBytes());

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
