package com.phlox.server.handlers;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.utils.RadixTree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.naming.Context;

public class Router implements RequestHandler {
    public static String ORIGINAL_PATH = "original_path";
    private final Map<String, Map<String, HandlerWithMiddlewares>> routes = new HashMap<>();//method -> path -> route
    private final RadixTree<HandlerWithMiddlewares> prefixedRoutes = new RadixTree<>();

    public final Middlewares globalMiddlewares = new Middlewares();

    public final Listener listener;

    private static class HandlerWithMiddlewares {
        final RequestHandler handler;
        final Middlewares middlewares;

        public HandlerWithMiddlewares(RequestHandler handler, Middlewares middlewares) {
            this.handler = handler;
            this.middlewares = middlewares;
        }
    }

    public static class Middlewares {
        final List<Middleware> preMiddlewares = new ArrayList<>();
        final List<Middleware> postMiddlewares = new ArrayList<>();

        public void addPreMiddleware(Middleware middleware) {
            preMiddlewares.add(middleware);
        }

        public void addPostMiddleware(Middleware middleware) {
            postMiddlewares.add(middleware);
        }

        public void reset() {
            preMiddlewares.clear();
            postMiddlewares.clear();
        }
    }

    public interface Listener {
        void onRequestResolved(RequestContext context, Request request, Response response);
    }

    public Router(Listener listener) {
        this.listener = listener;
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        String path = request.path;
        context.data.put(ORIGINAL_PATH, path);
        HandlerWithMiddlewares dest = findMatchingHandler(request.method, path);

        Response response = null;

        for (Middleware middleware : globalMiddlewares.preMiddlewares) {
            response = middleware.handleRequest(context, request);
            if (response != null) {
                break;
            }
        }

        if (response == null && dest != null) {
            if (dest.middlewares != null) {
                for (Middleware middleware : dest.middlewares.preMiddlewares) {
                    response = middleware.handleRequest(context, request);
                    if (response != null) {
                        break;
                    }
                }
            }

            if (response == null) {
                response = dest.handler.handleRequest(context, request);
            }

            context.response = response;

            if (dest.middlewares != null) {
                for (Middleware middleware : dest.middlewares.postMiddlewares) {
                    Response postMiddlewareResponse = middleware.handleRequest(context, request);
                    if (postMiddlewareResponse != null) {
                        response = postMiddlewareResponse;
                        break;
                    }
                }
            }
        }

        for (Middleware middleware : globalMiddlewares.postMiddlewares) {
            Response postMiddlewareResponse = middleware.handleRequest(context, request);
            if (postMiddlewareResponse != null) {
                response = postMiddlewareResponse;
                break;
            }
        }

        if (listener != null) {
            listener.onRequestResolved(context, request, response);
        }

        return response;
    }

    private HandlerWithMiddlewares findMatchingHandler(String method, String path) {
        Map<String, HandlerWithMiddlewares> methodRoutes = routes.get(method);
        if (methodRoutes == null) {
            return null;
        }
        HandlerWithMiddlewares handler = methodRoutes.get(path);
        if (handler != null) {
            return handler;
        }
        return prefixedRoutes.findLongestPrefix(path);
    }

    public void addRoute(String path, Set<String> methods, RequestHandler handler, Middlewares middlewares) {
        for (String method : methods) {
            Map<String, HandlerWithMiddlewares> methodRoutes = routes.get(method);
            if (methodRoutes == null) {
                methodRoutes = new HashMap<>();
                routes.put(method, methodRoutes);
            }
            //computeIfAbsent is available in Android starting API 23
            //Map<String, RequestHandler> methodRoutes = routes.computeIfAbsent(method, k -> new HashMap<>());
            methodRoutes.put(path, new HandlerWithMiddlewares(handler, middlewares));
        }
    }

    public void addRoute(String path, Set<String> methods, RequestHandler handler) {
        addRoute(path, methods, handler, null);
    }

    public void addRouteByPathPrefix(String pathPrefix, RequestHandler handler, Middlewares middlewares) {
        prefixedRoutes.put(pathPrefix, new HandlerWithMiddlewares(handler, middlewares));
    }

    public void addRouteByPathPrefix(String pathPrefix, RequestHandler handler) {
        addRouteByPathPrefix(pathPrefix, handler, null);
    }

    public void resetRoutes() {
        routes.clear();
        prefixedRoutes.clear();
    }
}
