package com.phlox.server.responses;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public class ResponseTest {

    @Test
    public void writesStatusLineHeadersAndBody() throws Exception {
        Response response = new TextResponse("héllo");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.writeOut(out);
        String text = out.toString("UTF-8");
        assertEquals("HTTP/1.1 200 OK\r\n" +
                "Content-Length: 6\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "héllo", text);
    }

    @Test
    public void lineBreakInHeaderValueIsRejected() {
        for (String value : new String[]{"a\r\nX-Injected: 1", "a\nb", "a\rb", "a\0b"}) {
            Response response = new Response(200, "OK");
            response.headers.add("Location", value);
            assertThrows(IllegalArgumentException.class, response::validateHead, value);
        }
    }

    @Test
    public void invalidHeaderNameIsRejected() {
        for (String name : new String[]{"X-Bad\r\nX-Injected", "With Space", "Colon:", ""}) {
            Response response = new Response(200, "OK");
            response.headers.add(name, "v");
            assertThrows(IllegalArgumentException.class, response::validateHead, name);
        }
    }

    @Test
    public void lineBreakInPhraseIsRejected() {
        Response response = new Response(200, "OK\r\nX-Injected: 1");
        assertThrows(IllegalArgumentException.class, response::validateHead);
    }

    @Test
    public void ordinaryHeadersPass() {
        Response response = new Response(200, "OK");
        response.headers.add("Content-Disposition", "attachment; filename=\"ünïcode name.txt\"");
        response.headers.add("rate_limit_remaining", "5");
        response.headers.add("X-Tab", "a\tb");
        assertDoesNotThrow(response::validateHead);
    }

    @Test
    public void nothingIsWrittenForAMalformedHead() {
        Response response = new TextResponse("body");
        response.headers.add("X-Evil", "a\r\nSet-Cookie: session=stolen");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThrows(IllegalArgumentException.class, () -> response.writeOut(out));
        assertEquals("", new String(out.toByteArray(), StandardCharsets.ISO_8859_1));
    }

    @Test
    public void headerNamesAreCaseInsensitive() {
        Response response = new Response(200, "OK");
        response.headers.add("content-type", "text/plain");
        response.setContentType("text/html");
        assertEquals(java.util.Collections.singletonList("text/html"), response.headers.getAll("Content-Type"));
        assertEquals("0", response.headers.get("CONTENT-LENGTH"));
    }

    @Test
    public void bodilessStatusesCarryNoContentLength() {
        assertEquals(null, StandardResponses.NO_CONTENT().headers.get("Content-Length"));
        assertEquals(null, StandardResponses.NOT_MODIFIED().headers.get("Content-Length"));
        assertEquals(null, new Response(101, "Switching Protocols").headers.get("Content-Length"));
        assertEquals("0", StandardResponses.NOT_FOUND().headers.get("Content-Length"));
        assertEquals("0", StandardResponses.UNAUTHORIZED().headers.get("Content-Length"));
    }

    @Test
    public void standardResponseCodes() {
        assertEquals(200, StandardResponses.OK("x").code);
        assertEquals(400, StandardResponses.BAD_REQUEST().code);
        assertEquals(403, StandardResponses.FORBIDDEN().code);
        assertEquals(404, StandardResponses.NOT_FOUND("x").code);
        assertEquals(405, StandardResponses.METHOD_NOT_ALLOWED(new String[]{"GET", "HEAD"}).code);
        assertEquals("GET, HEAD", StandardResponses.METHOD_NOT_ALLOWED(new String[]{"GET", "HEAD"}).headers.get("Allow"));
        assertEquals(409, StandardResponses.CONFLICT().code);
        assertEquals(413, StandardResponses.PAYLOAD_TOO_LARGE().code);
        assertEquals(415, StandardResponses.UNSUPPORTED_MEDIA_TYPE().code);
        assertEquals(500, StandardResponses.INTERNAL_SERVER_ERROR().code);
        Response moved = StandardResponses.MOVED_PERMANENTLY("/new");
        assertEquals(301, moved.code);
        assertEquals("/new", moved.headers.get("Location"));
        Response redirect = StandardResponses.REDIRECT("/x", 307);
        assertEquals(307, redirect.code);
        assertEquals("/x", redirect.headers.get("Location"));
        assertEquals("Thu, 01 Jan 1970 00:00:00 GMT", StandardResponses.NOT_MODIFIED(0).headers.get("Last-Modified"));
    }

    @Test
    public void textResponseLengthCountsBytes() {
        TextResponse response = new TextResponse(200, "OK", "привет", "text/plain; charset=utf-8");
        assertEquals(12, response.getContentLength());
        assertEquals("12", response.headers.get("Content-Length"));
        assertEquals("text/plain; charset=utf-8", response.getContentType());
    }

    @Test
    public void upgradeResponseHasNoContentLength() {
        UpgradeResponse response = UpgradeResponse.switchingProtocols("websocket", (socket, input, output) -> {});
        assertEquals(101, response.code);
        assertEquals(null, response.headers.get("Content-Length"));
        assertEquals("websocket", response.headers.get("Upgrade"));
        assertEquals("Upgrade", response.headers.get("Connection"));
        assertThrows(IllegalArgumentException.class, () -> new UpgradeResponse(101, "x", null));
    }

    @Test
    public void htmlTemplateResponse() throws Exception {
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("name", "<b>");
        HTMLTemplateResponse response = new HTMLTemplateResponse("<p>{{name}}</p>", data);
        assertEquals("text/html; charset=utf-8", response.getContentType());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.writeOut(out);
        assertEquals(true, out.toString("UTF-8").endsWith("\r\n\r\n<p>&lt;b&gt;</p>"));
    }
}
