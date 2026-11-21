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
    /** Header names are case-insensitive: "content-length" and "Content-Length" are one header. */
    public MultiMap<String, String> headers = MultiMap.caseInsensitive();
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
        //1xx and 204 must not carry a Content-Length (RFC 9110 8.6); on a 304 it would describe the
        //representation the client already has, which a bare 304 knows nothing about
        if (code / 100 != 1 && code != 204 && code != 304) {
            setContentLength(0);
        }
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

    /**
     * Checks that the status line and the headers can go on the wire as they are: header names
     * are tokens and no value or phrase contains a line break. Values often come from requests
     * (a path captured by a redirect rule, a file name in Content-Disposition), and a CR/LF in
     * one of them would let the client write headers - or a whole response - of its own.
     *
     * @throws IllegalArgumentException naming the first offending part
     */
    public void validateHead() {
        if (phrase != null && containsLineBreak(phrase)) {
            throw new IllegalArgumentException("Line break in the status phrase");
        }
        for (String key : headers.keys()) {
            if (!isToken(key)) {
                throw new IllegalArgumentException("Invalid header name: " + key);
            }
            for (String value : headers.getAll(key)) {
                if (value != null && containsLineBreak(value)) {
                    throw new IllegalArgumentException("Line break in the value of header " + key);
                }
            }
        }
    }

    private static boolean containsLineBreak(String s) {
        return s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\0') >= 0;
    }

    //RFC 7230 3.2.6 token
    private static boolean isToken(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean alphaNum = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (!alphaNum && "!#$%&'*+-.^_`|~".indexOf(c) < 0) {
                return false;
            }
        }
        return true;
    }

    protected String makeResponseHeader() {
        validateHead();
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
            byte[] buffer = new byte[8192];
            int readed;
            while ((readed = responseStream.read(buffer)) > 0) {
                output.write(buffer, 0, readed);
            }
        }
    }
}
