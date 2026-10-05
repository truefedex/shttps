package com.phlox.server.utils;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public final class HTTPUtils {
    private HTTPUtils() {}

    public static SimpleDateFormat getHTTPDateFormat() {
        SimpleDateFormat dateFormat = new SimpleDateFormat(
                "EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
        return dateFormat;
    }

    /**
     * Decodes %XX percent-encoding, interpreting the decoded bytes as UTF-8.
     * Unlike {@link URLDecoder}, '+' is left as-is (in a request path '+' is a literal
     * character, not a space) and characters that are illegal per RFC 3986 but sent
     * unencoded by real-world clients ('{', '[', '"', ...) are passed through.
     */
    public static String decodePercentEncoded(String s) {
        if (s.indexOf('%') < 0) return s;
        byte[] in = s.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        for (int i = 0; i < in.length; i++) {
            byte b = in[i];
            if (b == '%') {
                if (i + 2 >= in.length) {
                    throw new IllegalArgumentException("Incomplete percent-encoding: " + s);
                }
                int hi = Character.digit(in[i + 1], 16);
                int lo = Character.digit(in[i + 2], 16);
                if (hi < 0 || lo < 0) {
                    throw new IllegalArgumentException("Invalid percent-encoding: " + s);
                }
                out.write((hi << 4) | lo);
                i += 2;
            } else {
                out.write(b);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    public static void decodeURLEncodedNameValuePairs(String string, MultiMap<String, String> out) {
        String enc = "UTF-8";
        String[] params = string.split("&");
        for (int i = 0; i < params.length; i++) {
            int eq = params[i].indexOf("=");
            if (eq != -1) {
                try {
                    out.add(URLDecoder.decode(params[i].substring(0, eq), enc), URLDecoder.decode(params[i].substring(eq + 1), enc));
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static Map<String, String> parseCookieHeader(String cookieHeader) {
        Map<String, String> cookies = new LinkedHashMap<>();
        if (cookieHeader == null || cookieHeader.isEmpty()) {
            return cookies;
        }

        String[] pairs = cookieHeader.split(";");
        for (String pair : pairs) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) {
                String name = parts[0].trim();
                String rawValue = parts[1].trim();
                String decodedValue;

                try {
                    decodedValue = URLDecoder.decode(rawValue, StandardCharsets.UTF_8.name());
                } catch (Exception e) {
                    decodedValue = rawValue;
                }

                cookies.put(name, decodedValue);
            }
        }

        return cookies;
    }

    public static String buildSetCookieHeader(String name, String value, Map<String, Object> options) {
        if (name == null || value == null || name.isEmpty()) {
            throw new IllegalArgumentException("Cookie name and value must be non-null and name must be non-empty");
        }

        StringBuilder sb = new StringBuilder();
        try {
            sb.append(name).append("=")
                    .append(URLEncoder.encode(value, StandardCharsets.UTF_8.name()));
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }

        if (options != null) {
            for (Map.Entry<String, Object> entry : options.entrySet()) {
                String key = entry.getKey().toLowerCase();
                Object val = entry.getValue();

                switch (key) {
                    case "expires":
                        if (val instanceof Date) {
                            sb.append("; Expires=").append(getHTTPDateFormat().format((Date) val));
                        }
                        break;

                    case "max-age":
                        if (val instanceof Number) {
                            sb.append("; Max-Age=").append(((Number) val).longValue());
                        }
                        break;

                    case "domain":
                        sb.append("; Domain=").append(val.toString());
                        break;

                    case "path":
                        sb.append("; Path=").append(val.toString());
                        break;

                    case "secure":
                        if (Boolean.TRUE.equals(val)) {
                            sb.append("; Secure");
                        }
                        break;

                    case "httponly":
                        if (Boolean.TRUE.equals(val)) {
                            sb.append("; HttpOnly");
                        }
                        break;

                    case "samesite":
                        String samesite = val.toString();
                        if (samesite.equalsIgnoreCase("Strict") ||
                                samesite.equalsIgnoreCase("Lax") ||
                                samesite.equalsIgnoreCase("None")) {
                            sb.append("; SameSite=").append(samesite);
                        }
                        break;

                    default:
                        // unknown attribute - ignore
                        break;
                }
            }
        }

        return sb.toString();
    }

    public static MultiMap<String, String> parseHttpHeaders(String headersString) {
        MultiMap<String, String> headers = new MultiMap<>();
        String[] lines = headersString.split("\n");
        for (String line : lines) {
            String[] header = line.split(":", 2);
            if (header.length == 2) {
                String key = header[0].trim();
                String value = header[1].trim();
                if (!key.isEmpty() && !value.isEmpty()) {
                    headers.add(key, value);
                }
            }
        }
        return headers;
    }

    /** A media type and its parameters, as parsed by {@link #parseContentType}. */
    public static final class ContentType {
        /** Lower-cased "type/subtype", without parameters. */
        public final String mimeType;
        /** Parameter values by lower-cased name, unquoted. */
        public final Map<String, String> parameters;

        ContentType(String mimeType, Map<String, String> parameters) {
            this.mimeType = mimeType;
            this.parameters = Collections.unmodifiableMap(parameters);
        }
    }

    /**
     * Parses a Content-Type value (RFC 9110 8.3.1): {@code type/subtype *( ";" name=value )}.
     * Any number of parameters in any order, names case-insensitive, values token or
     * quoted-string. Malformed parameters are skipped rather than failing the whole header.
     */
    public static ContentType parseContentType(String header) {
        return parseValueWithParameters(header, true);
    }

    /**
     * Parses a Content-Disposition value the same way: the disposition type ("form-data",
     * "attachment") in {@link ContentType#mimeType}, then its parameters ("name", "filename").
     * Unlike {@link #parseContentType}, a backslash inside a quoted value is kept: browsers do not
     * escape with it in multipart file names (they percent-encode quotes), so "a\b.txt" is a name.
     */
    public static ContentType parseContentDisposition(String header) {
        return parseValueWithParameters(header, false);
    }

    private static ContentType parseValueWithParameters(String header, boolean backslashEscapes) {
        int length = header.length();
        int semicolon = header.indexOf(';');
        String mimeType = (semicolon < 0 ? header : header.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
        Map<String, String> parameters = new LinkedHashMap<>();
        int i = semicolon < 0 ? length : semicolon + 1;
        while (i < length) {
            int nameEnd = i;
            while (nameEnd < length && header.charAt(nameEnd) != '=' && header.charAt(nameEnd) != ';') {
                nameEnd++;
            }
            String name = header.substring(i, nameEnd).trim().toLowerCase(Locale.ROOT);
            if (nameEnd >= length || header.charAt(nameEnd) == ';') {
                //a parameter without a value
                i = nameEnd + 1;
                continue;
            }
            int valueStart = nameEnd + 1;
            while (valueStart < length && header.charAt(valueStart) == ' ') {
                valueStart++;
            }
            StringBuilder value = new StringBuilder();
            int next;
            if (valueStart < length && header.charAt(valueStart) == '"') {
                int j = valueStart + 1;
                while (j < length && header.charAt(j) != '"') {
                    char c = header.charAt(j);
                    if (backslashEscapes && c == '\\' && j + 1 < length) {
                        c = header.charAt(++j);
                    }
                    value.append(c);
                    j++;
                }
                next = header.indexOf(';', j);
            } else {
                next = header.indexOf(';', valueStart);
                value.append(header, valueStart, next < 0 ? length : next);
            }
            if (!name.isEmpty()) {
                parameters.put(name, value.toString().trim());
            }
            i = next < 0 ? length : next + 1;
        }
        return new ContentType(mimeType, parameters);
    }

    public static class Range {
        public long start;
        public long end;
        public long length;

        public Range(long start, long end) {
            this.start = start;
            this.end = end;
            length = end - start + 1;
        }
    }

    /**
     * Parses a Range header against a representation of {@code actualContentLength} bytes.
     * Supports "bytes=first-last", "bytes=first-" and the suffix form "bytes=-count"; a last
     * position past the end is clamped to it.
     *
     * @return the single range asked for, or null when the header should be ignored and the whole
     * representation sent (RFC 9110 14.2 allows that): another unit, a malformed or unsatisfiable
     * range, or several ranges - multipart/byteranges is not implemented
     */
    public static List<Range> parseRangeHeader(String rangeHeader, long actualContentLength) {
        int equals = rangeHeader.indexOf('=');
        if (equals < 0 || !rangeHeader.substring(0, equals).trim().equalsIgnoreCase("bytes")) {
            return null;
        }
        String spec = rangeHeader.substring(equals + 1);
        if (spec.indexOf(',') >= 0) {
            return null;
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        String first = spec.substring(0, dash).trim();
        String last = spec.substring(dash + 1).trim();
        long start;
        long end;
        if (first.isEmpty()) {
            //suffix range: the final "last" bytes
            long count = parseNonNegative(last);
            if (count <= 0) {
                return null;
            }
            start = Math.max(0, actualContentLength - count);
            end = actualContentLength - 1;
        } else {
            start = parseNonNegative(first);
            end = last.isEmpty() ? actualContentLength - 1 : parseNonNegative(last);
            if (start < 0 || end < 0 || end < start) {
                return null;
            }
            end = Math.min(end, actualContentLength - 1);
        }
        if (start >= actualContentLength || end < start) {
            return null;
        }
        return Collections.singletonList(new Range(start, end));
    }

    //digits only, at most 18 of them; -1 for anything else
    private static long parseNonNegative(String s) {
        if (s.isEmpty() || s.length() > 18) {
            return -1;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
        }
        return Long.parseLong(s);
    }

    public static boolean isTextContentType(String contentType) {
        return contentType.startsWith("text/") || 
            contentType.equals("application/json") || 
            contentType.equals("application/javascript") ||
            contentType.equals("application/xml") ||
            contentType.equals("application/xhtml+xml") ||
            contentType.equals("application/rss+xml") ||
            contentType.equals("application/atom+xml");
    }

    public static String normalizePath(String path) {
        if (path == null) {
            throw new IllegalArgumentException("Path cannot be null");
        }

        if (path.equals("..") || path.contains("/../") || path.startsWith("../") || path.endsWith("/..")) {
            throw new SecurityException("Malicious path detected: path contains '..'");
        }

        //the decoded path ends up in headers (redirect targets, Location of a folder) and log lines,
        //where a CR/LF would let the request write headers of its own
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new SecurityException("Control character in path");
            }
        }

        if (path.contains("//")) throw new SecurityException("Double slash prohibited for path");

        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        return path;
    }
}
