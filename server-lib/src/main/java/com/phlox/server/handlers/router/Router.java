package com.phlox.server.handlers.router;

import com.phlox.server.handlers.RequestHandler;
import com.phlox.server.handlers.router.middleware.DefaultHandlerExecutionChain;
import com.phlox.server.handlers.router.middleware.HandlerExecutionChain;
import com.phlox.server.handlers.router.middleware.Middleware;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.utils.RadixTree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Router implements RequestHandler {
    public static String ORIGINAL_PATH = "original_path";
    private final Map<String, Map<String, HandlerExecutionChain>> routes = new HashMap<>();//method -> path -> route
    private final RadixTree<HandlerExecutionChain> prefixedRoutes = new RadixTree<>();

    private final List<Middleware> globalMiddlewares;

    private final Listener listener;

    public interface Listener {
        void onRequestResolved(RequestContext context, Request request, Response response);
    }

    public Router(Listener listener, List<Middleware> globalMiddlewares) {
        this.listener = listener;
        this.globalMiddlewares = globalMiddlewares;
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        String path = request.path;
        context.data.put(ORIGINAL_PATH, path);
        HandlerExecutionChain dest = findMatchingChain(request.method, path);

        Response response = null;

        if (dest != null) {
            response = dest.proceed(context, request);
        }

        if (listener != null) {
            listener.onRequestResolved(context, request, response);
        }

        return response;
    }

    private HandlerExecutionChain findMatchingChain(String method, String path) {
        Map<String, HandlerExecutionChain> methodRoutes = routes.get(method);
        if (methodRoutes == null) {
            return null;
        }
        HandlerExecutionChain handler = methodRoutes.get(path);
        if (handler != null) {
            return handler;
        }
        return prefixedRoutes.findLongestPrefix(path);
    }

    public void addRoute(String path, Set<String> methods, RequestHandler handler, List<Middleware> middlewares) {
        for (String method : methods) {
            Map<String, HandlerExecutionChain> methodRoutes = routes.computeIfAbsent(method, k -> new HashMap<>());
            methodRoutes.put(path, new DefaultHandlerExecutionChain(combineMiddlewares(middlewares), handler));
        }
    }

    public void addRoute(String path, Set<String> methods, RequestHandler handler) {
        addRoute(path, methods, handler, null);
    }

    public void addRouteByPathPrefix(String pathPrefix, RequestHandler handler, List<Middleware> middlewares) {
        prefixedRoutes.put(pathPrefix, new DefaultHandlerExecutionChain(combineMiddlewares(middlewares), handler));
    }

    public void addRouteByPathPrefix(String pathPrefix, RequestHandler handler) {
        addRouteByPathPrefix(pathPrefix, handler, null);
    }

    private Middleware[] combineMiddlewares(List<Middleware> middlewares) {
        List<Middleware> allMiddlewares = new ArrayList<>(globalMiddlewares);
        if (middlewares != null) {
            allMiddlewares.addAll(middlewares);
        }
        return allMiddlewares.toArray(new Middleware[0]);
    }
}
