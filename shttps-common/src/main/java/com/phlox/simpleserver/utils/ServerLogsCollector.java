package com.phlox.simpleserver.utils;

import com.phlox.server.handlers.router.Router;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestBody;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.MultiMap;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedDeque;

public class ServerLogsCollector implements Router.Listener {
    //request/response bodies are kept in the log only when textual and not larger than this
    public static final int MAX_LOGGED_BODY_SIZE = 16 * 1024;

    final int maxLogSize;
    public final ConcurrentLinkedDeque<LogEntry> logs = new ConcurrentLinkedDeque<>();
    public Listener listener;

    public interface Listener {
        void onLogEntry(LogEntry entry);
    }

    /**
     * Snapshot of a handled request/response pair. Must not hold references to the live
     * {@link Request}/{@link Response} objects: the log outlives them by far and would
     * otherwise pin their buffered payloads, body/socket streams and response data.
     */
    public static class LogEntry {
        public final long startTimeMillis;
        public final long endTimeMillis;
        public final long connectionId;
        public final String hostAddress;
        public final String method;
        public final String path;
        public final String rawPathAndQuery;
        public final MultiMap<String, String> requestHeaders;
        //snapshot of the request body, only present for small textual bodies
        public final String requestBodyText;
        //when the request had a body that was not kept, a short reason shown in its place
        public final String requestBodyNote;
        public final int responseCode;
        public final String responsePhrase;
        public final MultiMap<String, String> responseHeaders;
        //snapshot of the response body, only present for small textual in-memory responses
        public final String responseBodyText;
        //when the response had a body that was not kept, a short reason shown in its place
        public final String responseBodyNote;

        public LogEntry(long endTimeMillis, Request request, String path, String requestBodyText,
                        String requestBodyNote, Response response, String responseBodyText,
                        String responseBodyNote) {
            this.startTimeMillis = request.time;
            this.endTimeMillis = endTimeMillis;
            this.connectionId = request.connectionId;
            this.hostAddress = request.hostAddress;
            this.method = request.method;
            this.path = path;
            this.rawPathAndQuery = request.rawPathAndQuery;
            this.requestHeaders = request.headers;
            this.requestBodyText = requestBodyText;
            this.requestBodyNote = requestBodyNote;
            this.responseCode = response.code;
            this.responsePhrase = response.phrase;
            this.responseHeaders = response.headers;
            this.responseBodyText = responseBodyText;
            this.responseBodyNote = responseBodyNote;
        }
    }

    public ServerLogsCollector(int maxLogSize) {
        this.maxLogSize = maxLogSize;
    }

    @Override
    public void onRequestResolved(RequestContext context, Request request, Response response) {
        if (response == null) {
            response = StandardResponses.NOT_FOUND();
        }
        String path = request.path;
        if (context.data.containsKey(Router.ORIGINAL_PATH)) {
            path = (String) context.data.get(Router.ORIGINAL_PATH);
        }
        String requestBodyText = takeRequestBodySnapshot(request);
        String requestBodyNote = requestBodyText == null ? describeSkippedRequestBody(request) : null;
        String responseBodyText = takeResponseBodySnapshot(response);
        String responseBodyNote = responseBodyText == null ? describeSkippedResponseBody(response) : null;
        LogEntry logEntry = new LogEntry(System.currentTimeMillis(), request, path,
                requestBodyText, requestBodyNote, response, responseBodyText, responseBodyNote);

        logs.addFirst(logEntry);

        while (logs.size() > maxLogSize) {
            logs.pollLast();
        }
        Listener listener = this.listener;
        if (listener != null) {
            listener.onLogEntry(logEntry);
        }
    }

    public void clear() {
        logs.clear();
    }

    static String takeRequestBodySnapshot(Request request) {
        RequestBody body = request.body;
        if (body == null || !isTextualContentType(request.contentType) || body.size() > MAX_LOGGED_BODY_SIZE) {
            return null;
        }
        Charset charset = StandardCharsets.UTF_8;
        if (request.charset != null) {
            try {
                charset = Charset.forName(request.charset);
            } catch (Exception ignored) {}
        }
        return new String(body.asBytes(), charset);
    }

    static String describeSkippedRequestBody(Request request) {
        RequestBody body = request.body;
        long size = body != null ? body.size() : request.contentLength;
        if (size <= 0) {
            return null;
        }
        String reason;
        if (body == null) {
            //streamed to storage (file upload, WebDAV PUT), parsed as form data or never read
            reason = "not captured";
        } else if (!isTextualContentType(request.contentType)) {
            reason = "binary or non-text content";
        } else {
            reason = "too large";
        }
        return skippedNote(reason, request.contentType, size);
    }

    /**
     * The response body can only be captured before it is written out when it is fully
     * in memory, i.e. backed by a {@link ByteArrayInputStream}: reading it never blocks
     * and mark/reset restores it for the client untouched. Everything else (file
     * downloads, zip/CGI/DB pipes) is a one-shot stream whose content may not even be
     * generated yet, so it is only described via {@link #describeSkippedResponseBody}.
     */
    static String takeResponseBodySnapshot(Response response) {
        InputStream stream = response.getStream();
        if (!(stream instanceof ByteArrayInputStream)) {
            return null;
        }
        ByteArrayInputStream bais = (ByteArrayInputStream) stream;
        int size = bais.available();
        String contentTypeHeader = response.getContentType();
        if (size == 0 || size > MAX_LOGGED_BODY_SIZE ||
                !isTextualContentType(extractMimeType(contentTypeHeader))) {
            return null;
        }
        byte[] bytes = new byte[size];
        bais.mark(size);
        int read = bais.read(bytes, 0, size);
        bais.reset();
        if (read <= 0) {
            return null;
        }
        return new String(bytes, 0, read, charsetFromContentType(contentTypeHeader));
    }

    static String describeSkippedResponseBody(Response response) {
        InputStream stream = response.getStream();
        if (stream == null) {
            return null;
        }
        String mimeType = extractMimeType(response.getContentType());
        if (!(stream instanceof ByteArrayInputStream)) {
            //body is generated while being written out, may be unbounded and can not be
            //consumed here without stealing it from the client
            return skippedNote("streamed", mimeType, response.getContentLength());
        }
        long size = ((ByteArrayInputStream) stream).available();
        if (size == 0) {
            return null;
        }
        String reason = isTextualContentType(mimeType) ? "too large" : "binary or non-text content";
        return skippedNote(reason, mimeType, size);
    }

    private static String skippedNote(String reason, String contentType, long size) {
        StringBuilder sb = new StringBuilder("skipped: ").append(reason);
        if (contentType != null || size > 0) {
            sb.append(" (");
            if (contentType != null) {
                sb.append(contentType);
                if (size > 0) {
                    sb.append(", ");
                }
            }
            if (size > 0) {
                sb.append(size).append(" bytes");
            }
            sb.append(')');
        }
        return sb.toString();
    }

    //response Content-Type headers may carry parameters ("text/html; charset=utf-8")
    static String extractMimeType(String contentTypeHeader) {
        if (contentTypeHeader == null) return null;
        int i = contentTypeHeader.indexOf(';');
        return (i >= 0 ? contentTypeHeader.substring(0, i) : contentTypeHeader).trim();
    }

    static Charset charsetFromContentType(String contentTypeHeader) {
        if (contentTypeHeader != null) {
            int i = contentTypeHeader.toLowerCase(Locale.ROOT).indexOf("charset=");
            if (i >= 0) {
                String name = contentTypeHeader.substring(i + "charset=".length()).trim();
                int end = name.indexOf(';');
                if (end >= 0) {
                    name = name.substring(0, end).trim();
                }
                name = name.replace("\"", "");
                try {
                    return Charset.forName(name);
                } catch (Exception ignored) {}
            }
        }
        return StandardCharsets.UTF_8;
    }

    static boolean isTextualContentType(String contentType) {
        if (contentType == null) return false;
        String type = contentType.toLowerCase(Locale.ROOT);
        return type.startsWith("text/") ||
                type.equals("application/json") ||
                type.equals("application/xml") ||
                type.equals("application/javascript") ||
                type.equals(Request.CONTENT_TYPE_URL_ENCODED_FORM) ||
                type.endsWith("+json") ||
                type.endsWith("+xml");
    }
}
