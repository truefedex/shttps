package com.phlox.server.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class DefaultRequestHeadersParserTest {

    private static Request parse(String target) throws Exception {
        String raw = "GET " + target + " HTTP/1.1\r\nHost: localhost\r\n\r\n";
        InputStream in = new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8));
        return new DefaultRequestHeadersParser().readRequestHeaders(in, "127.0.0.1");
    }

    @Test
    public void plainPathWithoutQuery() throws Exception {
        Request request = parse("/some/file.txt");
        assertEquals("/some/file.txt", request.path);
        assertEquals(0, request.queryParams.size());
    }

    @Test
    public void percentEncodedPath() throws Exception {
        Request request = parse("/files/my%20docs/%D0%BF%D1%80%D0%B8%D0%B2%D0%B5%D1%82.txt");
        assertEquals("/files/my docs/привет.txt", request.path);
    }

    @Test
    public void plusInPathStaysLiteral() throws Exception {
        Request request = parse("/files/a+b.txt");
        assertEquals("/files/a+b.txt", request.path);
    }

    @Test
    public void unencodedJsonCharsInQuery() throws Exception {
        //real-world clients send '{', '[', '"' unencoded in the query; java.net.URI rejects this
        Request request = parse("/api/db/table?table=changelog&filters={%22clauses%22:[%22stabilized=%22],%22args%22:[1]}&sort=date&sort-order=desc&limit=1");
        assertEquals("/api/db/table", request.path);
        assertEquals("changelog", request.queryParams.get("table"));
        assertEquals("{\"clauses\":[\"stabilized=\"],\"args\":[1]}", request.queryParams.get("filters"));
        assertEquals("date", request.queryParams.get("sort"));
        assertEquals("desc", request.queryParams.get("sort-order"));
        assertEquals("1", request.queryParams.get("limit"));
    }

    @Test
    public void plusInQueryDecodesToSpace() throws Exception {
        Request request = parse("/search?q=hello+world");
        assertEquals("hello world", request.queryParams.get("q"));
    }

    @Test
    public void emptyQueryIsTolerated() throws Exception {
        Request request = parse("/some/file.txt?");
        assertEquals("/some/file.txt", request.path);
        assertEquals(0, request.queryParams.size());
    }

    /** A rejected target is answered with 400; the cause says why. */
    private static void assertBadTarget(Class<? extends Throwable> cause, String target) {
        BadRequestException e = assertThrows(BadRequestException.class, () -> parse(target), target);
        assertEquals(400, e.code);
        assertInstanceOf(cause, e.getCause(), target);
    }

    @Test
    public void percentEncodedTraversalIsRejected() {
        assertBadTarget(SecurityException.class, "/files/%2e%2e/secret.txt");
        assertBadTarget(SecurityException.class, "/files/../secret.txt");
    }

    @Test
    public void nullByteInPathIsRejected() {
        assertBadTarget(SecurityException.class, "/files/a%00.txt");
    }

    @Test
    public void malformedPercentEncodingInPathIsRejected() {
        assertBadTarget(IllegalArgumentException.class, "/files/a%2");
        assertBadTarget(IllegalArgumentException.class, "/files/a%zz.txt");
        assertBadTarget(IllegalArgumentException.class, "/search?q=%zz");
    }

    @Test
    public void queryOnlyAffectsParamsNotPath() throws Exception {
        Request request = parse("/download?path=/files/../secret.txt");
        assertEquals("/download", request.path);
        assertNotNull(request.queryParams.get("path"));
    }

    private static Request parseWithContentType(String contentType) throws Exception {
        String raw = "POST /upload HTTP/1.1\r\nHost: localhost\r\nContent-Type: " + contentType + "\r\n\r\n";
        return new DefaultRequestHeadersParser().readRequestHeaders(
                new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)), "127.0.0.1");
    }

    @Test
    public void contentTypeWithSeveralParameters() throws Exception {
        //the old single-parameter regex left contentType null here, so uploads were rejected
        Request request = parseWithContentType("multipart/form-data; charset=UTF-8; boundary=----WebKitFormBoundary7MA4");
        assertEquals(Request.CONTENT_TYPE_MULTIPART_FORM, request.contentType);
        assertEquals("----WebKitFormBoundary7MA4", request.boundary);
        assertEquals("UTF-8", request.charset);
    }

    @Test
    public void contentTypeWithUnknownParameter() throws Exception {
        assertEquals("application/json", parseWithContentType("application/json; foo=bar").contentType);
    }

    @Test
    public void contentTypeIsCaseInsensitiveAndBoundaryUnquoted() throws Exception {
        Request request = parseWithContentType("Multipart/Form-Data; Boundary=\"abc def\"");
        assertEquals(Request.CONTENT_TYPE_MULTIPART_FORM, request.contentType);
        assertEquals("abc def", request.boundary);
        assertEquals(null, request.charset);
    }
}
