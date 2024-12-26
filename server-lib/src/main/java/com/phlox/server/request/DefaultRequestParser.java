package com.phlox.server.request;

import com.phlox.server.utils.HTTPUtils;
import com.phlox.server.utils.ScannerInputStream;
import com.phlox.server.utils.Utils;
import com.phlox.server.utils.SHTTPSLoggerProxy.Logger;
import com.phlox.server.utils.SHTTPSLoggerProxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DefaultRequestParser implements RequestParser {
    static final Logger logger = SHTTPSLoggerProxy.getLogger(DefaultRequestParser.class);
    public static final String DEFAULT_CHARSET_NAME = "UTF-8";
    //(attachment|form-data);(?:\s*name\s*=\s*"([^"]*)")*;*(?:\s*filename\s*=\s*"([^"]*)")*
    private final Pattern CONTENT_DISPOSITION_PATTERN =
            Pattern.compile("(file|attachment|form-data);(?:\\s*name\\s*=\\s*\"([^\"]*)\")*;*(?:\\s*filename\\s*=\\s*\"([^\"]*)\")*");
    //^([^;]*);*\s*(?:(?:boundary=(.*))|(?:charset=(.*)))?$
    private final Pattern CONTENT_TYPE_PATTERN =
            Pattern.compile("^([^;]*);*\\s*(?:(?:boundary=(.*))|(?:charset=(.*)))?$");

    @Override
    public Request parseRequestHeaders(InputStream input, String host) throws Exception {
        Request request = new Request();
        request.host = host;
        request.input = new ScannerInputStream(input);

        String line;
        int i = 0;
        while ((line = request.input.nextLine()) != null && !line.isEmpty()) {
            //logger.d(line);
            String[] parts;
            if (i == 0) {
                parts = line.split(" ");
                if (parts.length >= 2) {
                    request.method = parts[0].toUpperCase();
                    parts = parts[1].split("\\?");
                    request.path = URLDecoder.decode(parts[0], "UTF-8");
                    if (parts.length > 1) {
                        HTTPUtils.decodeURLEncodedNameValuePairs(parts[1], request.queryParams);
                    }
                }
            } else {
                int j = line.indexOf(":");
                if (j != -1) {
                    String name = line.substring(0, j);
                    String value = line.substring(j + 2);
                    request.headers.put(name, value);
                }
            }
            i++;
        }

        if (request.headers.isEmpty()) {
            return null;
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

        return request;
    }

    @Override
    public void parseRequestBody(Request request, BinaryDataConsumer customBinaryDataConsumer) throws Exception {
        BinaryDataConsumer binaryDataConsumer = customBinaryDataConsumer != null ? customBinaryDataConsumer : new DefaultBinaryDataConsumer();
        if (Request.CONTENT_TYPE_MULTIPART_FORM.equals(request.contentType)) {
            loadMultipartFormData(request, binaryDataConsumer);
        } else if (Request.CONTENT_TYPE_URL_ENCODED_FORM.equals(request.contentType)) {
            loadURLEncodedFormData(request);
        } else if (request.contentLength > 0) {
            try (OutputStream os = binaryDataConsumer.prepareBinaryOutputForRequestBodyData(request)) {
                Utils.copyStream(request.input, os, request.contentLength);
            }
        }
    }

    private void loadURLEncodedFormData(Request request) throws IOException {
        String data;
        if (request.contentLength != 0) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Utils.copyStream(request.input, baos, request.contentLength);
            data = baos.toString(DEFAULT_CHARSET_NAME);
        } else {
            data = request.input.nextLine();
        }
        if (data == null) {
            return;
        }
        HTTPUtils.decodeURLEncodedNameValuePairs(data, request.urlEncodedPostParams);
    }

    public void loadMultipartFormData(Request request, BinaryDataConsumer binaryDataConsumer) throws Exception {
        String currentBoundary = "--" + request.boundary;
        Map<String, String> partHeaders = new HashMap<>();

        String boundaryLine;
        try {
            boundaryLine = request.input.nextLine();
        } catch (Exception e) {
            return;
        }
        if (boundaryLine == null || !boundaryLine.equals(currentBoundary)) {
            return;
        }

        do {
            partHeaders.clear();
            String line;
            while ((line = request.input.nextLine()) != null && !line.isEmpty()) {
                logger.d(line);
                int j = line.indexOf(":");
                if (j != -1) {
                    String name = line.substring(0, j);
                    String value = line.substring(j + 2);
                    partHeaders.put(name, value);
                }
            }

            if (partHeaders.isEmpty()) {
                break;
            }

            String contentDispositionHeader = partHeaders.get(Request.HEADER_CONTENT_DISPOSITION);
            if (contentDispositionHeader == null) {
                return;
            }
            Matcher matcher = CONTENT_DISPOSITION_PATTERN.matcher(contentDispositionHeader);
            if (!matcher.find()) return;
            String contentDisposition = matcher.group(1);
            String name = matcher.group(2);
            String fileName = matcher.group(3);
            String contentType = partHeaders.get(Request.HEADER_CONTENT_TYPE);
            if (contentType != null && contentType.contains("boundary=")) {
                currentBoundary = "--" + contentType.substring(contentType.indexOf("=") + 1);
            }

            //read binary body
            byte[] boundaryBytes = ("\r\n" + currentBoundary).getBytes(DEFAULT_CHARSET_NAME);
            try (OutputStream output = binaryDataConsumer.prepareBinaryOutputForMultipartData(request, contentType, name, fileName, partHeaders)) {
                boolean foundDelimiter = request.input.readUntilDelimiter(boundaryBytes, output);
                if (!foundDelimiter) {
                    return;
                }
                line = request.input.nextLine();
                if (line == null || "--".equals(line)) {
                    break;
                }
            }
        } while (true);
    }
}
