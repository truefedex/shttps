package com.phlox.server.utils;

import com.phlox.server.handlers.StaticFileRequestHandler;
import com.phlox.server.utils.docfile.DocumentFile;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
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

    public static void decodeURLEncodedNameValuePairs(String string, Map<String, String> out) {
        String enc = "UTF-8";
        String[] params = string.split("&");
        for (int i = 0; i < params.length; i++) {
            String[] keyValue = params[i].split("=");
            if (keyValue.length == 2) {
                try {
                    out.put(URLDecoder.decode(keyValue[0], enc), URLDecoder.decode(keyValue[1], enc));
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static Map<String, String> parseHttpHeaders(String headersString) {
        Map<String, String> headers = new HashMap<>();
        String[] lines = headersString.split("\n");
        for (String line : lines) {
            String[] header = line.split(":");
            if (header.length == 2) {
                String key = header[0].trim();
                String value = header[1].trim();
                if (key.length() > 0 && value.length() > 0) {
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
}
