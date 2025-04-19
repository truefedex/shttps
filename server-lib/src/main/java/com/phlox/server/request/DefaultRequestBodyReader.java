package com.phlox.server.request;

import com.phlox.server.utils.HTTPUtils;
import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.server.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DefaultRequestBodyReader implements RequestBodyReader {
    public static final String DEFAULT_CHARSET_NAME = "UTF-8";
    private final Pattern CONTENT_DISPOSITION_PATTERN =
            Pattern.compile("(file|attachment|form-data);(?:\\s*name\\s*=\\s*\"([^\"]*)\")*;*(?:\\s*filename\\s*=\\s*\"([^\"]*)\")*");

    static final SHTTPSLoggerProxy.Logger logger = SHTTPSLoggerProxy.getLogger(DefaultRequestBodyReader.class);

    public boolean bodyWasRead = false;

    @Override
    public void readRequestBody(Request request, BinaryDataConsumer customBinaryDataConsumer) throws Exception {
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
        bodyWasRead = true;
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

    private void loadMultipartFormData(Request request, BinaryDataConsumer binaryDataConsumer) throws Exception {
        String currentBoundary = "--" + request.boundary;
        Map<String, String> partHeaders = new HashMap<>();

        // Read until we find the first boundary delimiter, ignoring any preamble
        String line;
        do {
            line = request.input.nextLine();
            if (line == null) {
                return;
            }
        } while (!line.equals(currentBoundary));

        do {
            partHeaders.clear();
            // Read headers until empty line
            while ((line = request.input.nextLine()) != null && !line.isEmpty()) {
                logger.d(line);
                int j = line.indexOf(":");
                if (j != -1) {
                    String name = line.substring(0, j).trim().toLowerCase();
                    String value = line.substring(j + 1).trim();
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

            // Read part body until next boundary
            byte[] boundaryBytes = ("\r\n" + currentBoundary).getBytes(DEFAULT_CHARSET_NAME);
            try (OutputStream output = binaryDataConsumer.prepareBinaryOutputForMultipartData(request, contentType, name, fileName, partHeaders)) {
                boolean foundDelimiter = request.input.readUntilDelimiter(boundaryBytes, output);
                if (!foundDelimiter) {
                    return;
                }

                // Check if this is the final boundary
                line = request.input.nextLine();
                if (line == null || line.equals("--")) {
                    break;
                }
            }
        } while (true);
    }
}
