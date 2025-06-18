package com.phlox.server.handlers;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;

import java.util.Date;
import java.util.LinkedList;

public class LoggingMiddleware implements Middleware {
    final int maxLogSize;
    public final LinkedList<LogEntry> logs = new LinkedList<>();
    public Listener listener;

    public interface Listener {
        void onLogEntry(LogEntry entry);
    }

    public static class LogEntry {
        public Date time;
        public Request request;
        public Response response;

        public LogEntry(Date time, Request request, Response response) {
            this.time = time;
            this.request = request;
            this.response = response;
        }
    }

    public LoggingMiddleware(int maxLogSize) {
        this.maxLogSize = maxLogSize;
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        Response response = context.response;
        if (response == null) {
            return null;
        }
        if (context.data.containsKey(Router.ORIGINAL_PATH)) {
            request.path = (String) context.data.get(Router.ORIGINAL_PATH);
        }
        LogEntry logEntry = new LogEntry(new Date(), request, response);
        synchronized (logs) {
            if (logs.size() >= maxLogSize) {
                logs.removeLast();
            }
            logs.addFirst(logEntry);
        }
        Listener listener = this.listener;
        if (listener != null) {
            listener.onLogEntry(logEntry);
        }
        return response;
    }
}
