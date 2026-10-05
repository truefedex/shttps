package com.phlox.server.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * How a request and its body are delimited must have exactly one reading: anything a proxy in
 * front of the server could read differently is refused instead of guessed at.
 */
public class RequestFramingTest {

    private static Request parseHead(String head) throws Exception {
        return new DefaultRequestHeadersParser().readRequestHeaders(
                new ByteArrayInputStream(head.getBytes(StandardCharsets.ISO_8859_1)), "127.0.0.1");
    }

    private static Request withHeaders(String... headerLines) throws Exception {
        StringBuilder head = new StringBuilder("POST /upload HTTP/1.1\r\nHost: localhost\r\n");
        for (String line : headerLines) {
            head.append(line).append("\r\n");
        }
        return parseHead(head.append("\r\n").toString());
    }

    private static int badRequestCode(String head) {
        return assertThrows(BadRequestException.class, () -> parseHead(head), head).code;
    }

    @Test
    public void malformedRequestLinesAre400() {
        for (String line : new String[]{"GET /", "GET  / HTTP/1.1", "GET / HTTP/2.0", "GET / http/1.1",
                "/ HTTP/1.1", "G@T / HTTP/1.1", "GET / HTTP/1.1 extra", " GET / HTTP/1.1", "GET\t/ HTTP/1.1"}) {
            assertEquals(400, badRequestCode(line + "\r\nHost: x\r\n\r\n"), line);
        }
    }

    @Test
    public void methodIsUpperCased() throws Exception {
        assertEquals("GET", parseHead("get / HTTP/1.1\r\nHost: x\r\n\r\n").method);
    }

    @Test
    public void http10WithoutHeadersIsAValidRequest() throws Exception {
        Request request = parseHead("GET /index.html HTTP/1.0\r\n\r\n");
        assertEquals("GET", request.method);
        assertEquals("/index.html", request.path);
        assertEquals(0, request.headers.size());
    }

    @Test
    public void afewLeadingEmptyLinesAreTolerated() throws Exception {
        assertEquals("/", parseHead("\r\n\r\nGET / HTTP/1.1\r\nHost: x\r\n\r\n").path);
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            many.append("\r\n");
        }
        assertEquals(400, badRequestCode(many + "GET / HTTP/1.1\r\n\r\n"));
    }

    @Test
    public void endOfStreamBeforeARequestIsNotAnError() throws Exception {
        assertNull(parseHead(""));
    }

    @Test
    public void endOfStreamInsideTheHeadIsAnError() {
        assertThrows(EOFException.class, () -> parseHead("GET / HTTP/1.1\r\nHost: x\r\n"));
    }

    @Test
    public void malformedHeaderLinesAre400() {
        for (String header : new String[]{"Host : x", " folded-continuation", "\tfolded", "NoColonHere",
                ": no-name", "Bad Name: x", "Bad\u0001Name: x"}) {
            assertEquals(400, badRequestCode("GET / HTTP/1.1\r\n" + header + "\r\n\r\n"), header);
        }
    }

    @Test
    public void headerNamesAreLowerCasedAndValuesTrimmed() throws Exception {
        Request request = withHeaders("X-Custom:   spaced value  ", "X-Empty:");
        assertEquals("spaced value", request.headers.get("x-custom"));
        assertEquals("", request.headers.get("x-empty"));
    }

    @Test
    public void contentLengthMustBeDigits() {
        for (String value : new String[]{"+5", "-5", "5a", "0x10", "", "1 2", "1234567890123456789"}) {
            assertThrows(BadRequestException.class, () -> withHeaders("Content-Length: " + value), value);
        }
    }

    @Test
    public void repeatedContentLengthMustAgree() throws Exception {
        assertEquals(5, withHeaders("Content-Length: 5", "Content-Length: 5").contentLength);
        assertEquals(5, withHeaders("Content-Length: 5, 5").contentLength);
        assertThrows(BadRequestException.class, () -> withHeaders("Content-Length: 5", "Content-Length: 6"));
        assertThrows(BadRequestException.class, () -> withHeaders("Content-Length: 5, 6"));
    }

    @Test
    public void contentLengthFramesTheBody() throws Exception {
        Request request = withHeaders("Content-Length: 12");
        assertEquals(12, request.contentLength);
        assertInstanceOf(FixedLengthBodyStream.class, request.bodyStream);
        assertFalse(request.requestToCloseConnection);
    }

    @Test
    public void onlyPlainChunkedTransferEncodingIsSupported() {
        for (String[] headers : new String[][]{{"Transfer-Encoding: gzip"}, {"Transfer-Encoding: gzip, chunked"},
                {"Transfer-Encoding: chunked, chunked"}, {"Transfer-Encoding: chunked", "Transfer-Encoding: gzip"}}) {
            BadRequestException e = assertThrows(BadRequestException.class, () -> withHeaders(headers),
                    String.join(" / ", headers));
            assertEquals(501, e.code);
        }
    }

    @Test
    public void transferEncodingWinsOverContentLengthAndClosesTheConnection() throws Exception {
        Request request = withHeaders("Content-Length: 100", "Transfer-Encoding: Chunked");
        assertEquals(-1, request.contentLength);
        assertInstanceOf(ChunkedBodyStream.class, request.bodyStream);
        assertTrue(request.requestToCloseConnection);
    }

    private static InputStream wire(String data) {
        return new ByteArrayInputStream(data.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void readAll(InputStream in) throws IOException {
        byte[] buffer = new byte[64];
        while (in.read(buffer) != -1) {
            //discard
        }
    }

    @Test
    public void chunkSizeMustBePlainHex() {
        for (String size : new String[]{"+5", "-0", "0x5", "5 5", "", "1234567890abcdef"}) {
            ChunkedBodyStream body = new ChunkedBodyStream(wire(size + "\r\nhello\r\n0\r\n\r\n"));
            BadRequestException e = assertThrows(BadRequestException.class, () -> readAll(body), size);
            assertEquals(400, e.code);
        }
    }

    @Test
    public void chunkSizeMayCarryExtensionsAndWhitespace() throws Exception {
        ChunkedBodyStream body = new ChunkedBodyStream(wire("5 ;ext=1\r\nhello\r\n0\r\n\r\n"));
        byte[] buffer = new byte[16];
        assertEquals(5, body.read(buffer));
        assertEquals("hello", new String(buffer, 0, 5, StandardCharsets.ISO_8859_1));
        assertEquals(-1, body.read(buffer));
    }

    @Test
    public void chunkLinesNeedCrlf() {
        for (String wire : new String[]{"5\nhello\r\n0\r\n\r\n", "5\r\nhello\n0\r\n\r\n", "5\r\nhello\r\n0\n\r\n",
                "5\r\nhello\r\n0\r\nTrailer: x\n\r\n"}) {
            ChunkedBodyStream body = new ChunkedBodyStream(wire(wire));
            assertThrows(BadRequestException.class, () -> readAll(body), wire);
        }
    }

    @Test
    public void connectionEndingInsideChunkDataReadsAsEndOfBody() throws Exception {
        //documented behaviour: the connection is dead anyway, the body just ends
        ChunkedBodyStream body = new ChunkedBodyStream(wire("5\r\nhel"));
        readAll(body);
        assertTrue(body.isFullyConsumed());
    }

    @Test
    public void connectionEndingInsideChunkFramingIsAnError() {
        ChunkedBodyStream body = new ChunkedBodyStream(wire("5\r\nhello"));
        assertThrows(EOFException.class, () -> readAll(body));
    }
}
