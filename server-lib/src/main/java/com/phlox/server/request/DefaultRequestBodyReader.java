package com.phlox.server.request;

import com.phlox.server.utils.HTTPUtils;
import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.server.utils.ScannerInputStream;
import com.phlox.server.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

    @Override
    public void readRequestBody(Request request, RequestBodyConsumer customRequestBodyConsumer) throws Exception {
        RequestBodyConsumer requestBodyConsumer = customRequestBodyConsumer != null ? customRequestBodyConsumer : new DefaultRequestBodyConsumer();
        BodyInputStream body = request.bodyStream;
        if (body == null || body.isFullyConsumed()) {
            return;
        }
        if (Request.CONTENT_TYPE_MULTIPART_FORM.equals(request.contentType)) {
            loadMultipartFormData(request, new ScannerInputStream(body), requestBodyConsumer);
        } else if (Request.CONTENT_TYPE_URL_ENCODED_FORM.equals(request.contentType)) {
            loadURLEncodedFormData(request, body);
        } else {
            try (OutputStream os = requestBodyConsumer.prepareBinaryOutputForRequestBodyData(request)) {
                Utils.copyStream(body, os);
            }
        }
    }

    private void loadURLEncodedFormData(Request request, InputStream body) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Utils.copyStream(body, baos);
        String data = baos.toString(DEFAULT_CHARSET_NAME);
        HTTPUtils.decodeURLEncodedNameValuePairs(data, request.urlEncodedPostParams);
    }

    private void loadMultipartFormData(Request request, ScannerInputStream input, RequestBodyConsumer requestBodyConsumer) throws Exception {
        String currentBoundary = "--" + request.boundary;
        Map<String, String> partHeaders = new HashMap<>();

        // Read until we find the first boundary delimiter, ignoring any preamble
        String line;
        do {
            line = input.nextLine();
            if (line == null) {
                return;
            }
        } while (!line.equals(currentBoundary));

        do {
            partHeaders.clear();
            // Read headers until empty line
            while ((line = input.nextLine("UTF-8")) != null && !line.isEmpty()) {
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
            try (OutputStream output = requestBodyConsumer.prepareBinaryOutputForMultipartData(request, contentType, name, fileName, partHeaders)) {
                boolean foundDelimiter = input.readUntilDelimiter(boundaryBytes, output);
                if (!foundDelimiter) {
                    return;
                }

                // Check if this is the final boundary
                line = input.nextLine();
                if (line == null || line.equals("--")) {
                    break;
                }
            }
        } while (true);
    }
}
