package com.phlox.simpleserver.utils;

import com.phlox.server.handlers.router.Router;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;

import java.util.concurrent.ConcurrentLinkedDeque;

public class ServerLogsCollector implements Router.Listener {
    final int maxLogSize;
    public final ConcurrentLinkedDeque<LogEntry> logs = new ConcurrentLinkedDeque<>();
    public Listener listener;

    public interface Listener {
        void onLogEntry(LogEntry entry);
    }

    public static class LogEntry {
        public final long startTimeMillis;
        public final long endTimeMillis;
        public final Request request;
        public final Response response;

        public LogEntry(long startTimeMillis, long endTimeMillis, Request request, Response response) {
            this.startTimeMillis = startTimeMillis;
            this.endTimeMillis = endTimeMillis;
            this.request = request;
            this.response = response;
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
        if (context.data.containsKey(Router.ORIGINAL_PATH)) {
            request.path = (String) context.data.get(Router.ORIGINAL_PATH);
        }
        LogEntry logEntry = new LogEntry(request.time, System.currentTimeMillis(),
                request, response);

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
}
