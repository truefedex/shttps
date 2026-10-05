package com.phlox.server.request;

import com.phlox.server.utils.HTTPUtils;
import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.server.utils.ScannerInputStream;
import com.phlox.server.utils.PayloadTooLargeException;
import com.phlox.server.utils.SizeLimitedByteArrayOutputStream;
import com.phlox.server.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

public class DefaultRequestBodyReader implements RequestBodyReader {
    public static final String DEFAULT_CHARSET_NAME = "UTF-8";
    /** A url-encoded form is parsed in memory, so it is capped like any other in-memory body. */
    public static final int MAX_URL_ENCODED_FORM_SIZE = DefaultRequestBodyConsumer.MAX_MULTIPART_DATA_SIZE;
    public static final int MAX_MULTIPART_PARTS = 1000;
    public static final int MAX_MULTIPART_HEADER_LINE_LENGTH = 8 * 1024;
    public static final int MAX_MULTIPART_HEADERS_PER_PART = 100;
    private static final java.util.List<String> KNOWN_DISPOSITIONS = java.util.Arrays.asList("form-data", "attachment", "file");

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
        ByteArrayOutputStream baos = new SizeLimitedByteArrayOutputStream(MAX_URL_ENCODED_FORM_SIZE);
        Utils.copyStream(body, baos);
        String data = baos.toString(DEFAULT_CHARSET_NAME);
        HTTPUtils.decodeURLEncodedNameValuePairs(data, request.urlEncodedPostParams);
    }

    private void loadMultipartFormData(Request request, ScannerInputStream input, RequestBodyConsumer requestBodyConsumer) throws Exception {
        if (request.boundary == null || request.boundary.isEmpty()) {
            //there is no way to find the parts; this used to look for "--null"
            throw new BadRequestException("Multipart body without a boundary");
        }
        final String delimiter = "--" + request.boundary;
        Map<String, String> partHeaders = new HashMap<>();

        // Read until we find the first boundary delimiter, ignoring any preamble
        String line;
        do {
            line = nextLine(input, "ASCII");
            if (line == null) {
                return;
            }
        } while (!line.equals(delimiter));

        int partCount = 0;
        do {
            if (++partCount > MAX_MULTIPART_PARTS) {
                throw new PayloadTooLargeException("Multipart body has more than " + MAX_MULTIPART_PARTS + " parts");
            }
            partHeaders.clear();
            // Read headers until empty line
            int headerCount = 0;
            while ((line = nextLine(input, "UTF-8")) != null && !line.isEmpty()) {
                if (++headerCount > MAX_MULTIPART_HEADERS_PER_PART) {
                    throw new IllegalStateException("Multipart part has more than " +
                            MAX_MULTIPART_HEADERS_PER_PART + " headers");
                }
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
            //parameters in any order, quoted or not ("filename" before "name" used to lose the name)
            HTTPUtils.ContentType disposition = HTTPUtils.parseContentDisposition(contentDispositionHeader);
            if (!KNOWN_DISPOSITIONS.contains(disposition.mimeType)) {
                return;
            }
            String name = disposition.parameters.get("name");
            String fileName = disposition.parameters.get("filename");
            //a part that is itself multipart (RFC 2388's multipart/mixed for several files) is
            //handed over as it is. Its boundary used to replace the outer one for the rest of
            //the body and was never switched back, which broke every part after it
            String contentType = partHeaders.get(Request.HEADER_CONTENT_TYPE);

            // Read part body until next boundary
            byte[] boundaryBytes = ("\r\n" + delimiter).getBytes(DEFAULT_CHARSET_NAME);
            try (OutputStream output = requestBodyConsumer.prepareBinaryOutputForMultipartData(request, contentType, name, fileName, partHeaders)) {
                boolean foundDelimiter = input.readUntilDelimiter(boundaryBytes, output);
                if (!foundDelimiter) {
                    return;
                }

                // Check if this is the final boundary
                line = nextLine(input, "ASCII");
                if (line == null || line.equals("--")) {
                    break;
                }
            }
        } while (true);
    }

    private static String nextLine(ScannerInputStream input, String charset) throws IOException {
        try {
            return input.nextLine(charset, MAX_MULTIPART_HEADER_LINE_LENGTH);
        } catch (ScannerInputStream.LimitExceededException e) {
            throw new IllegalStateException("Multipart line longer than " + MAX_MULTIPART_HEADER_LINE_LENGTH + " bytes");
        }
    }
}
