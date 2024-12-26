package com.phlox.server.handlers;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.request.RequestParser;
import com.phlox.server.responses.Response;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class RoutingRequestHandler implements RequestHandler {
    public static String ORIGINAL_PATH = "original_path";

    private final List<Route> routes = new ArrayList<>();

    @Override
    public Response handleRequest(RequestContext context, Request request, RequestParser requestParser) throws Exception {
        for (int i = 0; i < routes.size(); i++) {
            Route r = routes.get(i);
            if (r.match(request)) {
                if (!r.path.isEmpty() && !r.path.equals("/")) {
                    String originalPath = request.path;
                    request.path = request.path.substring(r.path.length());
                    context.data.put(ORIGINAL_PATH, originalPath);
                }
                if (request.path.isEmpty()) {
                    request.path = "/";
                }
                return r.handler.handleRequest(context, request, requestParser);
            }
        }
        return null;
    }

    public void addRoute(String path, Set<String> methods, RequestHandler handler) {
        routes.add(new Route(path, methods, handler));
        Collections.sort(routes, (r1, r2) -> Integer.compare(r2.path.length(), r1.path.length()));
    }

    public void addRoute(String route, RequestHandler handler) {
        addRoute(route, null, handler);
    }

    private static class Route {
        final String path;
        final Set<String> methods;
        final RequestHandler handler;

        public Route(String path, Set<String> methods, RequestHandler handler) {
            this.path = path;
            this.methods = methods;
            this.handler = handler;
        }

        public boolean match(Request request) {
            return request.path.startsWith(path) &&
                    (methods == null || methods.contains(request.method));
        }
    }
}
