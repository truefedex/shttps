package com.phlox.server.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

public class HTTPUtilsTest {

    @Test
    public void normalizePathAddsLeadingSlash() {
        assertEquals("/a/b", HTTPUtils.normalizePath("a/b"));
        assertEquals("/a/b", HTTPUtils.normalizePath("/a/b"));
        assertEquals("/", HTTPUtils.normalizePath("/"));
    }

    @Test
    public void normalizePathAllowsDotsInsideNames() {
        assertEquals("/a..b/..c/d..", HTTPUtils.normalizePath("/a..b/..c/d.."));
    }

    @Test
    public void normalizePathRejectsParentSegments() {
        for (String path : new String[]{"..", "/..", "../a", "/a/../b", "/a/.."}) {
            assertThrows(SecurityException.class, () -> HTTPUtils.normalizePath(path), path);
        }
    }

    @Test
    public void normalizePathRejectsDoubleSlash() {
        assertThrows(SecurityException.class, () -> HTTPUtils.normalizePath("//evil.example/x"));
        assertThrows(SecurityException.class, () -> HTTPUtils.normalizePath("/a//b"));
    }

    @Test
    public void normalizePathRejectsControlCharacters() {
        for (String path : new String[]{"/a\0b", "/a\r\nX-Injected: 1", "/a\nb", "/a\rb", "/a\tb", "/a\u007Fb"}) {
            assertThrows(SecurityException.class, () -> HTTPUtils.normalizePath(path), path);
        }
    }

    @Test
    public void normalizePathRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> HTTPUtils.normalizePath(null));
    }

    @Test
    public void decodedLineBreakIsCaughtByNormalize() {
        String decoded = HTTPUtils.decodePercentEncoded("/old/a%0d%0aX-Injected:%201");
        assertThrows(SecurityException.class, () -> HTTPUtils.normalizePath(decoded));
    }

    @Test
    public void decodePercentEncodedHandlesUtf8AndLeavesPlus() {
        assertEquals("/a b/привет+c", HTTPUtils.decodePercentEncoded("/a%20b/%D0%BF%D1%80%D0%B8%D0%B2%D0%B5%D1%82+c"));
        assertEquals("{\"x\":[1]}", HTTPUtils.decodePercentEncoded("{%22x%22:[1]}"));
        assertEquals("no escapes", HTTPUtils.decodePercentEncoded("no escapes"));
        assertThrows(IllegalArgumentException.class, () -> HTTPUtils.decodePercentEncoded("%4"));
        assertThrows(IllegalArgumentException.class, () -> HTTPUtils.decodePercentEncoded("%zz"));
    }

    @Test
    public void urlEncodedPairs() {
        MultiMap<String, String> out = new MultiMap<>();
        HTTPUtils.decodeURLEncodedNameValuePairs("a=1&b=hello+world&a=2&empty=&novalue&c=%26%3D", out);
        assertEquals(java.util.Arrays.asList("1", "2"), out.getAll("a"));
        assertEquals("hello world", out.get("b"));
        assertEquals("", out.get("empty"));
        assertFalse(out.containsKey("novalue"));
        assertEquals("&=", out.get("c"));
    }

    @Test
    public void cookieHeader() {
        java.util.Map<String, String> cookies = HTTPUtils.parseCookieHeader("session=abc%20def; theme=dark;broken; x=a=b");
        assertEquals("abc def", cookies.get("session"));
        assertEquals("dark", cookies.get("theme"));
        assertEquals("a=b", cookies.get("x"));
        assertFalse(cookies.containsKey("broken"));
        assertTrue(HTTPUtils.parseCookieHeader("").isEmpty());
        assertTrue(HTTPUtils.parseCookieHeader(null).isEmpty());
    }

    @Test
    public void setCookieHeader() {
        java.util.Map<String, Object> options = new java.util.LinkedHashMap<>();
        options.put("Path", "/");
        options.put("Max-Age", 3600);
        options.put("HttpOnly", true);
        options.put("Secure", false);
        options.put("SameSite", "Strict");
        options.put("Expires", new java.util.Date(0));
        options.put("unknown", "ignored");
        assertEquals("id=a+b%3B; Path=/; Max-Age=3600; HttpOnly; SameSite=Strict; Expires=Thu, 01 Jan 1970 00:00:00 GMT",
                HTTPUtils.buildSetCookieHeader("id", "a b;", options));
        java.util.Map<String, Object> badSameSite = new java.util.HashMap<>();
        badSameSite.put("samesite", "Bogus");
        assertEquals("id=v", HTTPUtils.buildSetCookieHeader("id", "v", badSameSite));
        assertThrows(IllegalArgumentException.class, () -> HTTPUtils.buildSetCookieHeader("", "v", null));
        assertThrows(IllegalArgumentException.class, () -> HTTPUtils.buildSetCookieHeader("id", null, null));
    }

    @Test
    public void headerBlock() {
        MultiMap<String, String> headers = HTTPUtils.parseHttpHeaders("Content-Type: text/html\nX-A: 1\nX-A: 2\nbroken line\nEmpty:\n");
        assertEquals("text/html", headers.get("Content-Type"));
        assertEquals(java.util.Arrays.asList("1", "2"), headers.getAll("X-A"));
        assertFalse(headers.containsKey("Empty"));
        assertEquals(3, headers.size());
    }

    @Test
    public void textContentTypes() {
        assertTrue(HTTPUtils.isTextContentType("text/plain"));
        assertTrue(HTTPUtils.isTextContentType("application/json"));
        assertTrue(HTTPUtils.isTextContentType("application/xhtml+xml"));
        assertFalse(HTTPUtils.isTextContentType("image/png"));
        assertFalse(HTTPUtils.isTextContentType("application/octet-stream"));
    }

    @Test
    public void contentTypeWithParameters() {
        HTTPUtils.ContentType type = HTTPUtils.parseContentType("multipart/form-data; charset=UTF-8; boundary=----x");
        assertEquals("multipart/form-data", type.mimeType);
        assertEquals("UTF-8", type.parameters.get("charset"));
        assertEquals("----x", type.parameters.get("boundary"));
    }

    @Test
    public void contentTypeNormalization() {
        HTTPUtils.ContentType type = HTTPUtils.parseContentType(" Multipart/Form-Data ;BOUNDARY=\"quoted ; value\" ; Charset = utf-8 ");
        assertEquals("multipart/form-data", type.mimeType);
        assertEquals("quoted ; value", type.parameters.get("boundary"));
        assertEquals("utf-8", type.parameters.get("charset"));

        assertEquals("application/json", HTTPUtils.parseContentType("application/json; foo=bar").mimeType);
        assertEquals("text/plain", HTTPUtils.parseContentType("text/plain").mimeType);
        assertTrue(HTTPUtils.parseContentType("text/plain;").parameters.isEmpty());
        assertEquals("a\"b", HTTPUtils.parseContentType("x/y; p=\"a\\\"b\"").parameters.get("p"));
        assertTrue(HTTPUtils.parseContentType("x/y; novalue; =x").parameters.isEmpty());
    }

    private static String ranges(String header, long length) {
        java.util.List<HTTPUtils.Range> ranges = HTTPUtils.parseRangeHeader(header, length);
        if (ranges == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (HTTPUtils.Range range : ranges) {
            sb.append(range.start).append('-').append(range.end).append('/').append(range.length).append(' ');
        }
        return sb.toString().trim();
    }

    @Test
    public void rangeForms() {
        assertEquals("0-99/100", ranges("bytes=0-99", 1000));
        assertEquals("500-999/500", ranges("bytes=500-", 1000));
        //suffix: the last 300 bytes, not bytes 0..300
        assertEquals("700-999/300", ranges("bytes=-300", 1000));
        assertEquals("0-999/1000", ranges("bytes=-5000", 1000));
        assertEquals("10-19/10", ranges("Bytes = 10 - 19", 1000));
    }

    @Test
    public void rangeEndIsClampedToTheContent() {
        //otherwise Content-Length promises bytes that never come
        assertEquals("900-999/100", ranges("bytes=900-5000", 1000));
    }

    @Test
    public void unsatisfiableOrInvalidRangesAreIgnored() {
        //null: answer with the whole representation, which RFC 9110 14.2 allows
        for (String header : new String[]{"bytes=1000-", "bytes=1000-1001", "bytes=-0", "bytes=20-10",
                "items=0-5", "bytes", "bytes=", "bytes=a-b", "bytes=+1-5", "bytes=1--5", "bytes=-", "bytes=0-1,"}) {
            assertNull(ranges(header, 1000), header);
        }
        assertNull(ranges("bytes=0-0", 0));
    }

    @Test
    public void multipleRangesAreIgnored() {
        //serving only the first one as if it were all that was asked for would be wrong;
        //multipart/byteranges is not implemented, so the whole body is the honest answer
        assertNull(ranges("bytes=0-1,5-6", 1000));
    }
}
