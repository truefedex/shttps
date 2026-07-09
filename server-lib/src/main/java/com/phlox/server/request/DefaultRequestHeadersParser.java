package com.phlox.server.request;

import com.phlox.server.utils.HTTPUtils;
import com.phlox.server.utils.ScannerInputStream;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DefaultRequestHeadersParser implements RequestHeadersParser {

    private final Pattern CONTENT_TYPE_PATTERN =
            Pattern.compile("^([^;]*);*\\s*(?:(?:boundary=(.*))|(?:charset=(.*)))?$");

    @Override
    public Request readRequestHeaders(InputStream input, String host) throws Exception {
        Request request = new Request();
        request.hostAddress = host;
        request.input = new ScannerInputStream(input);

        String line;
        int i = 0;
        while ((line = request.input.nextLine("UTF-8")) != null && !line.isEmpty()) {
            //handle first line of headers
            String[] parts;
            if (i == 0) {
                parts = line.split(" ");
                if (parts.length >= 2) {
                    request.method = parts[0].toUpperCase();
                    request.rawPathAndQuery = parts[1];
                    //do not use java.net.URI here: it is a strict RFC 3986 parser, while
                    //real-world clients send unencoded '{', '[', '"' etc. in the query
                    int q = request.rawPathAndQuery.indexOf('?');
                    String pathEncoded = q != -1 ? request.rawPathAndQuery.substring(0, q) : request.rawPathAndQuery;
                    request.path = HTTPUtils.normalizePath(HTTPUtils.decodePercentEncoded(pathEncoded));
                    if (q != -1) {
                        HTTPUtils.decodeURLEncodedNameValuePairs(request.rawPathAndQuery.substring(q + 1), request.queryParams);
                    }
                }
            } else {
                parts = line.split(":", 2);
                if (parts.length == 2) {
                    String name = parts[0].trim().toLowerCase();
                    String value = parts[1].trim();
                    request.headers.add(name, value);
                }
            }
            i++;
        }

        if (request.headers.size() == 0) {
            if (request.method != null) {
                throw new IllegalArgumentException("Can not parse request headers");
            } else {
                return null;
            }
        }

        String contentTypeHeader = request.headers.get(Request.HEADER_CONTENT_TYPE);
        if (contentTypeHeader != null) {
            Matcher matcher = CONTENT_TYPE_PATTERN.matcher(contentTypeHeader);
            if (matcher.find()) {
                request.contentType = matcher.group(1);
                request.boundary = matcher.group(2);
                request.charset = matcher.group(3);
            }
        }

        String contentLengthHeader = request.headers.get(Request.HEADER_CONTENT_LENGTH);
        if (contentLengthHeader != null) {
            request.contentLength = Long.parseLong(contentLengthHeader);
        }

        String cookies = request.headers.get(Request.HEADER_COOKIE);
        if (cookies != null) {
            request.cookies = HTTPUtils.parseCookieHeader(cookies);
        }

        String connectionHeader = request.headers.get(Request.HEADER_CONNECTION);
        request.requestToCloseConnection = Request.CONNECTION_CLOSE.equalsIgnoreCase(connectionHeader);

        request.expectContinue = Request.EXPECTATION_100_CONTINUE.equalsIgnoreCase(
                request.headers.get(Request.HEADER_EXPECT));

        String transferEncodingHeader = request.headers.get(Request.HEADER_TRANSFER_ENCODING);
        if (transferEncodingHeader != null) {
            if (!Request.TRANSFER_ENCODING_CHUNKED.equalsIgnoreCase(transferEncodingHeader.trim())) {
                throw new IllegalArgumentException("Unsupported transfer encoding: " + transferEncodingHeader);
            }
            //RFC 7230 3.3.3: when Transfer-Encoding is present, Content-Length must be
            //ignored (otherwise the mismatch can be abused for request smuggling)
            request.contentLength = -1;
            request.bodyStream = new ChunkedBodyStream(request.input);
        } else {
            //body framed by Content-Length (no header or 0 means no body)
            request.bodyStream = new FixedLengthBodyStream(request.input, request.contentLength);
        }

        return request;
    }
}
