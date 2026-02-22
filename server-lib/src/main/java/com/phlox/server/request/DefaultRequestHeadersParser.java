package com.phlox.server.request;

import com.phlox.server.utils.HTTPUtils;
import com.phlox.server.utils.ScannerInputStream;

import java.io.InputStream;
import java.net.URLDecoder;
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
                    int q = request.rawPathAndQuery.indexOf("?");
                    if (q != -1) {
                        String pathEncoded = request.rawPathAndQuery.substring(0, q);
                        request.path = URLDecoder.decode(pathEncoded, "UTF-8");
                        String queryEncoded = request.rawPathAndQuery.substring(q + 1);
                        HTTPUtils.decodeURLEncodedNameValuePairs(queryEncoded, request.queryParams);
                    } else {
                        request.path = URLDecoder.decode(request.rawPathAndQuery, "UTF-8");
                    }
                }
            } else {
                parts = line.split(":", 2);
                if (parts.length == 2) {
                    String name = parts[0].trim().toLowerCase();
                    String value = parts[1].trim();
                    request.headers.put(name, value);
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
            if (!matcher.find()) return request;
            request.contentType = matcher.group(1);
            request.boundary = matcher.group(2);
            request.charset = matcher.group(3);
        }

        String contentLengthHeader = request.headers.get(Request.HEADER_CONTENT_LENGTH);
        if (contentLengthHeader != null) {
            request.contentLength = Long.parseLong(contentLengthHeader);
        }

        String transferEncodingHeader = request.headers.get(Request.HEADER_TRANSFER_ENCODING);
        if (Request.TRANSFER_ENCODING_CHUNKED.equalsIgnoreCase(transferEncodingHeader)) {
            throw new IllegalArgumentException("Chunked transfer encoding is not supported");
        }

        String cookies = request.headers.get(Request.HEADER_COOKIE);
        if (cookies != null) {
            request.cookies = HTTPUtils.parseCookieHeader(cookies);
        }

        String connectionHeader = request.headers.get(Request.HEADER_CONNECTION);
        request.requestToCloseConnection = Request.CONNECTION_CLOSE.equalsIgnoreCase(connectionHeader);

        return request;
    }
}
