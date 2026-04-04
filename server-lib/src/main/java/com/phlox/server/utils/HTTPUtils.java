package com.phlox.server.utils;

import com.phlox.server.handlers.StaticFileRequestHandler;
import com.phlox.server.utils.docfile.DocumentFile;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
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

    public static void decodeURLEncodedNameValuePairs(String string, MultiMap<String, String> out) {
        String enc = "UTF-8";
        String[] params = string.split("&");
        for (int i = 0; i < params.length; i++) {
            int eq = params[i].indexOf("=");
            if (eq != -1) {
                try {
                    out.put(URLDecoder.decode(params[i].substring(0, eq), enc), URLDecoder.decode(params[i].substring(eq + 1), enc));
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
                            SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
                            sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
                            sb.append("; Expires=").append(sdf.format((Date) val));
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
                    headers.put(key, value);
                }
            }
        }
        return headers;
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

    public static List<Range> parseRangeHeader(String rangeHeader, long actualContentLength) {
        String[] rangesStrs = rangeHeader.split("=")[1].split(",");
        List<Range> ranges = new ArrayList<>();
        for (String rangeStr: rangesStrs) {
            String[] parts = rangeStr.split("-");
            long start = 0;
            long end = actualContentLength - 1;
            try {
                start = Long.parseLong(parts[0]);
            } catch (Exception e){}
            try {
                end = Long.parseLong(parts[1]);
            } catch (Exception e){}
            ranges.add(new Range(start, end));
        }

        return ranges;
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
}
