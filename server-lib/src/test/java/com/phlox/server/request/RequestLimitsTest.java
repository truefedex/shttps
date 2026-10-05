package com.phlox.server.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.phlox.server.utils.ScannerInputStream;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Everything a client sends before authentication is buffered in memory, so every part of it
 * has to be bounded.
 */
public class RequestLimitsTest {

    private static Request parseHead(String head) throws Exception {
        return new DefaultRequestHeadersParser().readRequestHeaders(
                new ByteArrayInputStream(head.getBytes(StandardCharsets.ISO_8859_1)), "127.0.0.1");
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    @Test
    public void ordinaryHeadIsAccepted() throws Exception {
        StringBuilder head = new StringBuilder("GET /" + repeat('a', 4000) + " HTTP/1.1\r\n");
        for (int i = 0; i < DefaultRequestHeadersParser.DEFAULT_MAX_HEADER_COUNT; i++) {
            head.append("X-H").append(i).append(": v\r\n");
        }
        head.append("\r\n");
        Request request = parseHead(head.toString());
        assertNotNull(request);
        assertEquals("v", request.headers.get("x-h99"));
    }

    @Test
    public void tooLongRequestLineIs414() {
        BadRequestException e = assertThrows(BadRequestException.class, () ->
                parseHead("GET /" + repeat('a', DefaultRequestHeadersParser.DEFAULT_MAX_REQUEST_LINE_LENGTH) + " HTTP/1.1\r\n\r\n"));
        assertEquals(414, e.code);
    }

    @Test
    public void tooLongHeaderLineIs431() {
        BadRequestException e = assertThrows(BadRequestException.class, () ->
                parseHead("GET / HTTP/1.1\r\nCookie: " + repeat('a', DefaultRequestHeadersParser.DEFAULT_MAX_LINE_LENGTH) + "\r\n\r\n"));
        assertEquals(431, e.code);
    }

    @Test
    public void tooManyHeadersIs431() {
        StringBuilder head = new StringBuilder("GET / HTTP/1.1\r\n");
        for (int i = 0; i <= DefaultRequestHeadersParser.DEFAULT_MAX_HEADER_COUNT; i++) {
            head.append("X-H").append(i).append(": v\r\n");
        }
        head.append("\r\n");
        BadRequestException e = assertThrows(BadRequestException.class, () -> parseHead(head.toString()));
        assertEquals(431, e.code);
    }

    @Test
    public void tooLargeHeadersInTotalIs431() {
        //each line is within the line limit, together they are not
        StringBuilder head = new StringBuilder("GET / HTTP/1.1\r\n");
        String value = repeat('v', 8000);
        for (int i = 0; i < 9; i++) {
            head.append("X-H").append(i).append(": ").append(value).append("\r\n");
        }
        head.append("\r\n");
        BadRequestException e = assertThrows(BadRequestException.class, () -> parseHead(head.toString()));
        assertEquals(431, e.code);
    }

    private static Request bodyRequest(String contentType, byte[] body) {
        Request request = new Request();
        request.method = Request.METHOD_POST;
        request.contentType = contentType;
        request.input = new ScannerInputStream(new ByteArrayInputStream(body));
        request.contentLength = body.length;
        request.bodyStream = new FixedLengthBodyStream(request.input, body.length);
        return request;
    }

    @Test
    public void urlEncodedFormIsCapped() {
        byte[] body = ("a=" + repeat('x', DefaultRequestBodyReader.MAX_URL_ENCODED_FORM_SIZE)).getBytes(StandardCharsets.US_ASCII);
        Request request = bodyRequest(Request.CONTENT_TYPE_URL_ENCODED_FORM, body);
        assertThrows(IllegalStateException.class, () -> new DefaultRequestBodyReader().readRequestBody(request));
    }

    @Test
    public void multipartPartCountIsCapped() {
        String boundary = "B";
        StringBuilder body = new StringBuilder("--B\r\n");
        for (int i = 0; i <= DefaultRequestBodyReader.MAX_MULTIPART_PARTS; i++) {
            body.append("Content-Disposition: form-data; name=\"f").append(i).append("\"\r\n\r\nv\r\n--B\r\n");
        }
        Request request = bodyRequest(Request.CONTENT_TYPE_MULTIPART_FORM, body.toString().getBytes(StandardCharsets.US_ASCII));
        request.boundary = boundary;
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new DefaultRequestBodyReader().readRequestBody(request));
        assertEquals("Multipart body has more than 1000 parts", e.getMessage());
    }

    @Test
    public void multipartHeaderLineIsCapped() {
        String body = "--B\r\nContent-Disposition: form-data; name=\"" +
                repeat('n', DefaultRequestBodyReader.MAX_MULTIPART_HEADER_LINE_LENGTH) + "\"\r\n\r\nv\r\n--B--\r\n";
        Request request = bodyRequest(Request.CONTENT_TYPE_MULTIPART_FORM, body.getBytes(StandardCharsets.US_ASCII));
        request.boundary = "B";
        assertThrows(IllegalStateException.class, () -> new DefaultRequestBodyReader().readRequestBody(request));
    }

    @Test
    public void multipartTotalSizeIsCapped() {
        //every part is under the per-part limit, all of them together are over the total one
        int partSize = DefaultRequestBodyConsumer.MAX_MULTIPART_DATA_SIZE - 1024;
        int parts = (int) (DefaultRequestBodyConsumer.MAX_MULTIPART_TOTAL_SIZE / partSize) + 1;
        String value = repeat('x', partSize);
        StringBuilder body = new StringBuilder("--B\r\n");
        for (int i = 0; i < parts; i++) {
            body.append("Content-Disposition: form-data; name=\"f").append(i).append("\"\r\n\r\n")
                    .append(value).append("\r\n--B\r\n");
        }
        Request request = bodyRequest(Request.CONTENT_TYPE_MULTIPART_FORM, body.toString().getBytes(StandardCharsets.US_ASCII));
        request.boundary = "B";
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new DefaultRequestBodyReader().readRequestBody(request));
        assertEquals("Multipart body exceeds the limit of " + DefaultRequestBodyConsumer.MAX_MULTIPART_TOTAL_SIZE + " bytes",
                e.getMessage());
    }
}
