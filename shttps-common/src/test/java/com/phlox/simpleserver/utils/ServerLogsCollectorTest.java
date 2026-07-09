package com.phlox.simpleserver.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.phlox.server.request.DefaultRequestBodyConsumer;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.TextResponse;
import com.phlox.server.utils.SizeLimitedByteArrayOutputStream;

import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class ServerLogsCollectorTest {

    private static Request requestWithBody(String contentType, byte[] bodyBytes) {
        Request request = new Request();
        request.method = Request.METHOD_POST;
        request.path = "/api/test";
        request.contentType = contentType;
        DefaultRequestBodyConsumer.RequestBodyImpl body = new DefaultRequestBodyConsumer.RequestBodyImpl(
                new SizeLimitedByteArrayOutputStream(Integer.MAX_VALUE));
        body.baos.write(bodyBytes, 0, bodyBytes.length);
        request.body = body;
        return request;
    }

    @Test
    public void keepsSmallTextualBody() {
        Request request = requestWithBody("application/json", "{\"key\":\"value\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals("{\"key\":\"value\"}", ServerLogsCollector.takeRequestBodySnapshot(request));
    }

    @Test
    public void respectsRequestCharset() {
        Request request = requestWithBody("text/plain", "café".getBytes(StandardCharsets.ISO_8859_1));
        request.charset = "ISO-8859-1";
        assertEquals("café", ServerLogsCollector.takeRequestBodySnapshot(request));
    }

    @Test
    public void skipsNonTextualBody() {
        Request request = requestWithBody("application/octet-stream", new byte[]{0, 1, 2, 3});
        assertNull(ServerLogsCollector.takeRequestBodySnapshot(request));
    }

    @Test
    public void skipsUnknownContentType() {
        Request request = requestWithBody(null, "text".getBytes(StandardCharsets.UTF_8));
        assertNull(ServerLogsCollector.takeRequestBodySnapshot(request));
    }

    @Test
    public void skipsTooLargeBody() {
        byte[] big = new byte[ServerLogsCollector.MAX_LOGGED_BODY_SIZE + 1];
        Arrays.fill(big, (byte) 'a');
        Request request = requestWithBody("text/plain", big);
        assertNull(ServerLogsCollector.takeRequestBodySnapshot(request));
    }

    @Test
    public void skipsAbsentBody() {
        Request request = new Request();
        request.contentType = "text/plain";
        assertNull(ServerLogsCollector.takeRequestBodySnapshot(request));
    }

    @Test
    public void recognizesTextualContentTypes() {
        assertTrue(ServerLogsCollector.isTextualContentType("text/html"));
        assertTrue(ServerLogsCollector.isTextualContentType("application/json"));
        assertTrue(ServerLogsCollector.isTextualContentType("application/xml"));
        assertTrue(ServerLogsCollector.isTextualContentType("application/x-www-form-urlencoded"));
        assertTrue(ServerLogsCollector.isTextualContentType("application/soap+xml"));
        assertTrue(ServerLogsCollector.isTextualContentType("application/problem+json"));
        assertTrue(ServerLogsCollector.isTextualContentType("TEXT/PLAIN"));
        assertFalse(ServerLogsCollector.isTextualContentType("application/octet-stream"));
        assertFalse(ServerLogsCollector.isTextualContentType("image/png"));
        assertFalse(ServerLogsCollector.isTextualContentType("multipart/form-data"));
        assertFalse(ServerLogsCollector.isTextualContentType(null));
    }

    @Test
    public void logEntrySnapshotsRequestAndResponse() {
        ServerLogsCollector collector = new ServerLogsCollector(10);
        Request request = requestWithBody("text/plain", "hello".getBytes(StandardCharsets.UTF_8));
        request.rawPathAndQuery = "/api/test?x=1";
        request.hostAddress = "192.168.0.2";
        request.connectionId = 42;
        request.headers.put("user-agent", "junit");

        collector.onRequestResolved(new RequestContext(null), request, new Response(200, "OK"));

        ServerLogsCollector.LogEntry entry = collector.logs.peekFirst();
        assertNotNull(entry);
        assertEquals("POST", entry.method);
        assertEquals("/api/test", entry.path);
        assertEquals("/api/test?x=1", entry.rawPathAndQuery);
        assertEquals("192.168.0.2", entry.hostAddress);
        assertEquals(42, entry.connectionId);
        assertEquals("junit", entry.requestHeaders.get("user-agent"));
        assertEquals("hello", entry.requestBodyText);
        assertNull(entry.requestBodyNote);
        assertEquals(200, entry.responseCode);
        assertEquals("OK", entry.responsePhrase);
    }

    @Test
    public void notesBinaryBody() {
        Request request = requestWithBody("image/png", new byte[]{0, 1, 2, 3});
        assertEquals("skipped: binary or non-text content (image/png, 4 bytes)",
                ServerLogsCollector.describeSkippedRequestBody(request));
    }

    @Test
    public void notesTooLargeBody() {
        byte[] big = new byte[ServerLogsCollector.MAX_LOGGED_BODY_SIZE + 1];
        Arrays.fill(big, (byte) 'a');
        Request request = requestWithBody("text/plain", big);
        assertEquals("skipped: too large (text/plain, " + big.length + " bytes)",
                ServerLogsCollector.describeSkippedRequestBody(request));
    }

    @Test
    public void notesUncapturedBodyByContentLength() {
        Request request = new Request();
        request.method = Request.METHOD_PUT;
        request.contentType = "application/octet-stream";
        request.contentLength = 1048576;
        assertEquals("skipped: not captured (application/octet-stream, 1048576 bytes)",
                ServerLogsCollector.describeSkippedRequestBody(request));
    }

    @Test
    public void noNoteWithoutBody() {
        Request request = new Request();
        request.method = Request.METHOD_GET;
        assertNull(ServerLogsCollector.describeSkippedRequestBody(request));

        Request emptyPost = new Request();
        emptyPost.method = Request.METHOD_POST;
        emptyPost.contentLength = 0;
        assertNull(ServerLogsCollector.describeSkippedRequestBody(emptyPost));
    }

    @Test
    public void capturesInMemoryTextualResponseBody() {
        Response response = new TextResponse("{\"ok\":true}", "application/json");
        assertEquals("{\"ok\":true}", ServerLogsCollector.takeResponseBodySnapshot(response));
    }

    @Test
    public void responseBodyCaptureLeavesStreamIntactForClient() throws IOException {
        Response response = new TextResponse("<html>hello</html>", "text/html");
        assertEquals("<html>hello</html>", ServerLogsCollector.takeResponseBodySnapshot(response));
        byte[] sentToClient = response.getStream().readAllBytes();
        assertEquals("<html>hello</html>", new String(sentToClient, StandardCharsets.UTF_8));
    }

    @Test
    public void respectsResponseContentTypeParameters() {
        //WebDAV-style content type with charset parameter
        Response response = new TextResponse("café".getBytes(StandardCharsets.ISO_8859_1),
                "application/xml; charset=ISO-8859-1");
        assertEquals("café", ServerLogsCollector.takeResponseBodySnapshot(response));
    }

    @Test
    public void notesStreamedResponseBody() {
        Response response = new Response("application/json", 100,
                new BufferedInputStream(new ByteArrayInputStream(new byte[100])));
        assertNull(ServerLogsCollector.takeResponseBodySnapshot(response));
        assertEquals("skipped: streamed (application/json, 100 bytes)",
                ServerLogsCollector.describeSkippedResponseBody(response));
    }

    @Test
    public void notesBinaryResponseBody() {
        Response response = new Response("image/png", 4, new ByteArrayInputStream(new byte[]{0, 1, 2, 3}));
        assertNull(ServerLogsCollector.takeResponseBodySnapshot(response));
        assertEquals("skipped: binary or non-text content (image/png, 4 bytes)",
                ServerLogsCollector.describeSkippedResponseBody(response));
    }

    @Test
    public void notesTooLargeResponseBody() {
        byte[] big = new byte[ServerLogsCollector.MAX_LOGGED_BODY_SIZE + 1];
        Arrays.fill(big, (byte) 'a');
        Response response = new TextResponse(big, "text/plain");
        assertNull(ServerLogsCollector.takeResponseBodySnapshot(response));
        assertEquals("skipped: too large (text/plain, " + big.length + " bytes)",
                ServerLogsCollector.describeSkippedResponseBody(response));
    }

    @Test
    public void noResponseNoteWithoutBody() {
        Response response = new Response(204, "No Content");
        assertNull(ServerLogsCollector.takeResponseBodySnapshot(response));
        assertNull(ServerLogsCollector.describeSkippedResponseBody(response));
    }

    @Test
    public void logEntryCarriesResponseBody() {
        ServerLogsCollector collector = new ServerLogsCollector(10);
        Request request = new Request();
        request.method = Request.METHOD_GET;
        request.path = "/api/db/query";

        collector.onRequestResolved(new RequestContext(null),
                request, new TextResponse(420, "Method Failure", "no such table: users"));

        ServerLogsCollector.LogEntry entry = collector.logs.peekFirst();
        assertNotNull(entry);
        assertEquals("no such table: users", entry.responseBodyText);
        assertNull(entry.responseBodyNote);
    }

    @Test
    public void logEntryCarriesBodyNoteInsteadOfText() {
        ServerLogsCollector collector = new ServerLogsCollector(10);
        Request request = requestWithBody("application/octet-stream", new byte[]{1, 2, 3});

        collector.onRequestResolved(new RequestContext(null), request, new Response(200, "OK"));

        ServerLogsCollector.LogEntry entry = collector.logs.peekFirst();
        assertNotNull(entry);
        assertNull(entry.requestBodyText);
        assertEquals("skipped: binary or non-text content (application/octet-stream, 3 bytes)",
                entry.requestBodyNote);
    }

    @Test
    public void logEntryUsesOriginalPathWhenRouterRewroteIt() {
        ServerLogsCollector collector = new ServerLogsCollector(10);
        Request request = new Request();
        request.method = Request.METHOD_GET;
        request.path = "/rewritten";
        RequestContext context = new RequestContext(null);
        context.data.put(com.phlox.server.handlers.router.Router.ORIGINAL_PATH, "/original");

        collector.onRequestResolved(context, request, new Response(200, "OK"));

        ServerLogsCollector.LogEntry entry = collector.logs.peekFirst();
        assertNotNull(entry);
        assertEquals("/original", entry.path);
    }
}
