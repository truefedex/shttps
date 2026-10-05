package com.phlox.server.request;

import com.phlox.server.utils.HTTPUtils;
import com.phlox.server.utils.ScannerInputStream;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class DefaultRequestHeadersParser implements RequestHeadersParser {
    /**
     * Longest request line, CRLF not counted. Generous because this server's own API passes JSON
     * (database filters, for one) in query strings.
     */
    public static final int DEFAULT_MAX_REQUEST_LINE_LENGTH = 64 * 1024;
    /** Longest header line, CRLF not counted. */
    public static final int DEFAULT_MAX_LINE_LENGTH = 8 * 1024;
    public static final int DEFAULT_MAX_HEADER_COUNT = 100;
    /** All header lines of one request together. */
    public static final int DEFAULT_MAX_HEADERS_SIZE = 64 * 1024;
    /** How long the head of a request may take to arrive, from its first byte to its empty line. */
    public static final long DEFAULT_HEAD_READ_TIMEOUT_MILLIS = 20_000;

    public volatile int maxRequestLineLength = DEFAULT_MAX_REQUEST_LINE_LENGTH;
    public volatile int maxLineLength = DEFAULT_MAX_LINE_LENGTH;
    public volatile int maxHeaderCount = DEFAULT_MAX_HEADER_COUNT;
    public volatile int maxHeadersSize = DEFAULT_MAX_HEADERS_SIZE;
    public volatile long headReadTimeoutMillis = DEFAULT_HEAD_READ_TIMEOUT_MILLIS;

    /** Empty lines tolerated before a request line (RFC 9112 2.2), e.g. after a previous body. */
    private static final int MAX_LEADING_EMPTY_LINES = 8;

    @Override
    public Request readRequestHeaders(InputStream input, String host) throws Exception {
        Request request = new Request();
        request.hostAddress = host;
        request.input = new ScannerInputStream(input);
        request.input.startDeadlineOnNextByte(headReadTimeoutMillis);

        String requestLine;
        int emptyLines = 0;
        do {
            requestLine = readHeadLine(request.input, true);
            if (requestLine == null) {
                //the connection ended before a new request started
                return null;
            }
        } while (requestLine.isEmpty() && ++emptyLines <= MAX_LEADING_EMPTY_LINES);
        parseRequestLine(request, requestLine);

        String line;
        int count = 0;
        long headersSize = 0;
        while ((line = readHeadLine(request.input, false)) != null && !line.isEmpty()) {
            headersSize += line.length();
            if (++count > maxHeaderCount || headersSize > maxHeadersSize) {
                throw BadRequestException.headersTooLarge("Request headers exceed the limit of " +
                        maxHeaderCount + " headers / " + maxHeadersSize + " bytes");
            }
            parseHeaderLine(request, line);
        }
        if (line == null) {
            throw new EOFException("Connection closed in the middle of the request head");
        }
        //the body that follows is not subject to the head deadline
        request.input.clearDeadline();

        String contentTypeHeader = request.headers.get(Request.HEADER_CONTENT_TYPE);
        if (contentTypeHeader != null) {
            HTTPUtils.ContentType contentType = HTTPUtils.parseContentType(contentTypeHeader);
            request.contentType = contentType.mimeType;
            request.boundary = contentType.parameters.get("boundary");
            request.charset = contentType.parameters.get("charset");
        }

        String cookies = request.headers.get(Request.HEADER_COOKIE);
        if (cookies != null) {
            request.cookies = HTTPUtils.parseCookieHeader(cookies);
        }

        String connectionHeader = request.headers.get(Request.HEADER_CONNECTION);
        request.requestToCloseConnection = Request.CONNECTION_CLOSE.equalsIgnoreCase(connectionHeader);

        request.expectContinue = Request.EXPECTATION_100_CONTINUE.equalsIgnoreCase(
                request.headers.get(Request.HEADER_EXPECT));

        parseBodyFraming(request);
        return request;
    }

    /**
     * "METHOD target HTTP/1.x", single spaces, nothing else - a lenient parse here is what lets
     * a request mean one thing to a proxy in front of us and another thing to us.
     */
    private static void parseRequestLine(Request request, String line) throws BadRequestException {
        String[] parts = line.split(" ", -1);
        if (parts.length != 3 || !isToken(parts[0]) || parts[1].isEmpty() ||
                !(parts[2].equals("HTTP/1.1") || parts[2].equals("HTTP/1.0"))) {
            throw new BadRequestException("Malformed request line");
        }
        request.method = parts[0].toUpperCase();
        request.rawPathAndQuery = parts[1];
        //do not use java.net.URI here: it is a strict RFC 3986 parser, while
        //real-world clients send unencoded '{', '[', '"' etc. in the query
        int q = request.rawPathAndQuery.indexOf('?');
        String pathEncoded = q != -1 ? request.rawPathAndQuery.substring(0, q) : request.rawPathAndQuery;
        try {
            request.path = HTTPUtils.normalizePath(HTTPUtils.decodePercentEncoded(pathEncoded));
            if (q != -1) {
                HTTPUtils.decodeURLEncodedNameValuePairs(request.rawPathAndQuery.substring(q + 1), request.queryParams);
            }
        } catch (IllegalArgumentException | SecurityException e) {
            throw new BadRequestException("Invalid request target: " + e.getMessage(), e);
        }
    }

    private static void parseHeaderLine(Request request, String line) throws BadRequestException {
        int colon = line.indexOf(':');
        //no whitespace before the colon and no obsolete line folding: both are ways to make
        //two parsers disagree about which header a line is (RFC 9112 5.1, 5.2)
        if (colon <= 0 || !isToken(line.substring(0, colon))) {
            throw new BadRequestException("Malformed header line");
        }
        request.headers.add(line.substring(0, colon).toLowerCase(), line.substring(colon + 1).trim());
    }

    /**
     * Decides how the body is delimited. Ambiguity here is request smuggling, so it is refused
     * rather than resolved by a guess.
     */
    private static void parseBodyFraming(Request request) throws BadRequestException {
        List<String> contentLengths = request.headers.getAll(Request.HEADER_CONTENT_LENGTH);
        if (!contentLengths.isEmpty()) {
            request.contentLength = parseContentLength(contentLengths);
        }

        List<String> transferEncodings = request.headers.getAll(Request.HEADER_TRANSFER_ENCODING);
        if (!transferEncodings.isEmpty()) {
            List<String> codings = new ArrayList<>();
            for (String value : transferEncodings) {
                for (String coding : value.split(",")) {
                    if (!coding.trim().isEmpty()) {
                        codings.add(coding.trim());
                    }
                }
            }
            if (codings.size() != 1 || !Request.TRANSFER_ENCODING_CHUNKED.equalsIgnoreCase(codings.get(0))) {
                throw new BadRequestException(501, "Not Implemented",
                        "Unsupported transfer encoding: " + String.join(", ", transferEncodings));
            }
            if (!contentLengths.isEmpty()) {
                //RFC 9112 6.1: Transfer-Encoding wins, and a request carrying both may have come
                //through something that framed it differently, so the connection is not reused
                request.requestToCloseConnection = true;
            }
            request.contentLength = -1;
            request.bodyStream = new ChunkedBodyStream(request.input);
        } else {
            //body framed by Content-Length (no header or 0 means no body)
            request.bodyStream = new FixedLengthBodyStream(request.input, request.contentLength);
        }
    }

    /**
     * Digits only: Long.parseLong would also take "+5" and "-5". Repeated values (as separate
     * headers or a list) are accepted only when they all agree.
     */
    private static long parseContentLength(List<String> values) throws BadRequestException {
        long result = -1;
        for (String value : values) {
            for (String item : value.split(",", -1)) {
                String digits = item.trim();
                if (digits.isEmpty() || digits.length() > 18) {
                    throw new BadRequestException("Invalid Content-Length");
                }
                for (int i = 0; i < digits.length(); i++) {
                    char c = digits.charAt(i);
                    if (c < '0' || c > '9') {
                        throw new BadRequestException("Invalid Content-Length");
                    }
                }
                long length = Long.parseLong(digits);
                if (result != -1 && result != length) {
                    throw new BadRequestException("Conflicting Content-Length values");
                }
                result = length;
            }
        }
        return result;
    }

    //RFC 9110 5.6.2 token
    private static boolean isToken(String s) {
        if (s.isEmpty()) {
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

    private String readHeadLine(ScannerInputStream input, boolean requestLine) throws IOException {
        try {
            return input.nextLine("UTF-8", requestLine ? maxRequestLineLength : maxLineLength);
        } catch (ScannerInputStream.LimitExceededException e) {
            throw requestLine ?
                    BadRequestException.uriTooLong("Request line longer than " + maxRequestLineLength + " bytes") :
                    BadRequestException.headersTooLarge("Header line longer than " + maxLineLength + " bytes");
        }
    }
}
