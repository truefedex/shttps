package com.phlox.server.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.phlox.server.utils.ScannerInputStream;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public class DefaultRequestBodyReaderTest {

    /**
     * Builds a request whose wire stream contains the given body followed by trailing
     * bytes that belong to the next pipelined request and must never be consumed.
     */
    private Request makeRequest(String body, String trailing) {
        Request request = new Request();
        request.method = Request.METHOD_POST;
        byte[] wire = (body + trailing).getBytes(StandardCharsets.UTF_8);
        request.input = new ScannerInputStream(new ByteArrayInputStream(wire));
        request.contentLength = body.getBytes(StandardCharsets.UTF_8).length;
        request.bodyStream = new FixedLengthBodyStream(request.input, request.contentLength);
        return request;
    }

    @Test
    public void readsRawBodyExactlyToContentLength() throws Exception {
        Request request = makeRequest("raw body content", "GET / HTTP/1.1\r\n");
        request.contentType = "application/octet-stream";

        new DefaultRequestBodyReader().readRequestBody(request);

        assertNotNull(request.body);
        assertEquals("raw body content", request.body.toString());
        assertTrue(request.bodyStream.isFullyConsumed());
    }

    @Test
    public void parsesUrlEncodedForm() throws Exception {
        Request request = makeRequest("login=user&password=p%40ss", "TRAILING");
        request.contentType = Request.CONTENT_TYPE_URL_ENCODED_FORM;

        new DefaultRequestBodyReader().readRequestBody(request);

        assertEquals("user", request.urlEncodedPostParams.get("login"));
        assertEquals("p@ss", request.urlEncodedPostParams.get("password"));
        assertTrue(request.bodyStream.isFullyConsumed());
    }

    @Test
    public void parsesMultipartFormData() throws Exception {
        String boundary = "----TESTBOUNDARY";
        String body = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"field1\"\r\n" +
                "\r\n" +
                "value1\r\n" +
                "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"file1\"; filename=\"a.txt\"\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "file contents here\r\n" +
                "--" + boundary + "--\r\n";
        Request request = makeRequest(body, "NEXT-REQUEST-DATA");
        request.contentType = Request.CONTENT_TYPE_MULTIPART_FORM;
        request.boundary = boundary;

        new DefaultRequestBodyReader().readRequestBody(request);

        assertEquals(2, request.multipartData.size());
        FormDataPart field = null, file = null;
        for (FormDataPart part : request.multipartData) {
            if ("field1".equals(part.name)) field = part;
            if ("file1".equals(part.name)) file = part;
        }
        assertNotNull(field);
        assertEquals("value1", field.getDataAsString());
        assertNotNull(file);
        assertEquals("a.txt", file.fileName);
        assertEquals("text/plain", file.contentType);
        assertEquals("file contents here", file.getDataAsString());
        assertTrue(request.bodyStream.isFullyConsumed());
        //the framing must protect the next request's bytes even from the boundary scanner
        assertEquals('N', request.input.read());
    }

    @Test
    public void truncatedMultipartBodyDoesNotReadPastContentLength() throws Exception {
        String boundary = "----TESTBOUNDARY";
        //body cut off in the middle of a part, no final boundary
        String body = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"field1\"\r\n" +
                "\r\n" +
                "value1 without terminating bound";
        Request request = makeRequest(body, "NEXT-REQUEST-DATA");
        request.contentType = Request.CONTENT_TYPE_MULTIPART_FORM;
        request.boundary = boundary;

        new DefaultRequestBodyReader().readRequestBody(request);

        //parsing gives up at body EOF instead of consuming the next request's bytes
        assertTrue(request.bodyStream.isFullyConsumed());
        assertEquals('N', request.input.read());
    }

    @Test
    public void parsesChunkedUrlEncodedForm() throws Exception {
        Request request = new Request();
        request.method = Request.METHOD_POST;
        String wire = "a\r\nlogin=user\r\n10\r\n&password=p%40ss\r\n0\r\n\r\nNEXT-REQUEST";
        request.input = new ScannerInputStream(new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)));
        request.contentLength = -1;
        request.bodyStream = new ChunkedBodyStream(request.input);
        request.contentType = Request.CONTENT_TYPE_URL_ENCODED_FORM;

        new DefaultRequestBodyReader().readRequestBody(request);

        assertEquals("user", request.urlEncodedPostParams.get("login"));
        assertEquals("p@ss", request.urlEncodedPostParams.get("password"));
        assertTrue(request.bodyStream.isFullyConsumed());
        assertEquals('N', request.input.read());
    }

    private static FormDataPart part(Request request, String name) {
        for (FormDataPart part : request.multipartData) {
            if (name.equals(part.name)) return part;
        }
        return null;
    }

    private Request multipart(String boundary, String body) {
        Request request = makeRequest(body, "NEXT");
        request.contentType = Request.CONTENT_TYPE_MULTIPART_FORM;
        request.boundary = boundary;
        return request;
    }

    @Test
    public void dispositionParametersInAnyOrder() throws Exception {
        //"filename" before "name" used to lose the name entirely
        Request request = multipart("B", "--B\r\n" +
                "Content-Disposition: form-data; filename=\"report; final.txt\"; name=\"upload\"\r\n" +
                "\r\n" +
                "data\r\n" +
                "--B--\r\n");
        new DefaultRequestBodyReader().readRequestBody(request);
        FormDataPart upload = part(request, "upload");
        assertNotNull(upload);
        assertEquals("report; final.txt", upload.fileName);
        assertEquals("data", upload.getDataAsString());
    }

    @Test
    public void backslashInAFileNameIsKept() throws Exception {
        Request request = multipart("B", "--B\r\n" +
                "Content-Disposition: form-data; name=\"f\"; filename=\"a\\b.txt\"\r\n" +
                "\r\n" +
                "x\r\n" +
                "--B--\r\n");
        new DefaultRequestBodyReader().readRequestBody(request);
        assertEquals("a\\b.txt", part(request, "f").fileName);
    }

    @Test
    public void nestedMultipartPartDoesNotHijackTheBoundary() throws Exception {
        Request request = multipart("OUTER", "--OUTER\r\n" +
                "Content-Disposition: form-data; name=\"files\"\r\n" +
                "Content-Type: multipart/mixed; boundary=INNER\r\n" +
                "\r\n" +
                "--INNER\r\nContent-Disposition: file; filename=\"a.txt\"\r\n\r\nA\r\n--INNER--\r\n" +
                "--OUTER\r\n" +
                "Content-Disposition: form-data; name=\"after\"\r\n" +
                "\r\n" +
                "still parsed\r\n" +
                "--OUTER--\r\n");
        new DefaultRequestBodyReader().readRequestBody(request);
        assertEquals("--INNER\r\nContent-Disposition: file; filename=\"a.txt\"\r\n\r\nA\r\n--INNER--",
                part(request, "files").getDataAsString());
        assertEquals("still parsed", part(request, "after").getDataAsString());
        assertEquals('N', request.input.read());
    }

    @Test
    public void multipartWithoutBoundaryIsABadRequest() {
        Request request = multipart(null, "--null\r\nContent-Disposition: form-data; name=\"x\"\r\n\r\ny\r\n--null--\r\n");
        org.junit.jupiter.api.Assertions.assertThrows(BadRequestException.class,
                () -> new DefaultRequestBodyReader().readRequestBody(request));
    }

    @Test
    public void emptyBodyIsNoOp() throws Exception {
        Request request = makeRequest("", "GET / HTTP/1.1\r\n");
        request.contentType = Request.CONTENT_TYPE_URL_ENCODED_FORM;

        new DefaultRequestBodyReader().readRequestBody(request);

        assertEquals(0, request.urlEncodedPostParams.size());
        assertEquals('G', request.input.read());
    }
}
