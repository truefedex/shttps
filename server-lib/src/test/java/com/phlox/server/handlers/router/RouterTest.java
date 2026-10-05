package com.phlox.server.handlers.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.phlox.server.handlers.router.middleware.Middleware;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class RouterTest {

    private static Request request(String method, String path) {
        Request request = new Request();
        request.method = method;
        request.path = path;
        return request;
    }

    private static Response handle(Router router, String method, String path) throws Exception {
        return router.handleRequest(new RequestContext(null), request(method, path));
    }

    private static String body(Response response) throws Exception {
        byte[] bytes = new byte[(int) response.getContentLength()];
        int read = response.getStream() == null ? 0 : response.getStream().read(bytes);
        return new String(bytes, 0, Math.max(read, 0), "UTF-8");
    }

    private static Router router() {
        return new Router(null, Collections.<Middleware>emptyList());
    }

    @Test
    public void exactRouteMatchesMethodAndPath() throws Exception {
        Router router = router();
        router.addRoute("/a", Collections.singleton(Request.METHOD_GET), (c, r) -> StandardResponses.OK("a"));
        assertEquals("a", body(handle(router, Request.METHOD_GET, "/a")));
        assertEquals(404, handle(router, Request.METHOD_POST, "/a").code);
        assertEquals(404, handle(router, Request.METHOD_GET, "/a/b").code);
    }

    @Test
    public void longestPrefixWinsAndIgnoresMethod() throws Exception {
        Router router = router();
        router.addRouteByPathPrefix("/", (c, r) -> StandardResponses.OK("root"));
        router.addRouteByPathPrefix("/api/", (c, r) -> StandardResponses.OK("api"));
        assertEquals("api", body(handle(router, Request.METHOD_DELETE, "/api/x/y")));
        assertEquals("root", body(handle(router, Request.METHOD_GET, "/other")));
    }

    @Test
    public void exactRouteBeatsPrefixAndMethodMismatchFallsBackToPrefix() throws Exception {
        Router router = router();
        router.addRouteByPathPrefix("/", (c, r) -> StandardResponses.OK("files"));
        router.addRoute("/api/list", Collections.singleton(Request.METHOD_GET), (c, r) -> StandardResponses.OK("list"));
        assertEquals("list", body(handle(router, Request.METHOD_GET, "/api/list")));
        assertEquals("files", body(handle(router, Request.METHOD_POST, "/api/list")));
    }

    @Test
    public void prefixMatchIsNotSegmentAware() throws Exception {
        //pinned on purpose: a prefix route registered without a trailing slash also takes
        //paths that merely start with the same characters
        Router router = router();
        router.addRouteByPathPrefix("/api/file", (c, r) -> StandardResponses.OK("file"));
        assertEquals("file", body(handle(router, Request.METHOD_GET, "/api/filesystem")));
    }

    @Test
    public void middlewaresRunGlobalFirstThenRouteInOrder() throws Exception {
        List<String> trace = new ArrayList<>();
        Middleware global = recording(trace, "global");
        Router router = new Router(null, Collections.singletonList(global));
        router.addRoute("/a", Collections.singleton(Request.METHOD_GET), (c, r) -> {
            trace.add("handler");
            return StandardResponses.OK("a");
        }, Arrays.asList(recording(trace, "route1"), recording(trace, "route2")));
        handle(router, Request.METHOD_GET, "/a");
        assertEquals(Arrays.asList("global", "route1", "route2", "handler"), trace);
    }

    @Test
    public void middlewareCanShortCircuit() throws Exception {
        List<String> trace = new ArrayList<>();
        Router router = router();
        router.addRoute("/a", Collections.singleton(Request.METHOD_GET), (c, r) -> {
            trace.add("handler");
            return StandardResponses.OK("a");
        }, Collections.singletonList((context, request, chain) -> StandardResponses.UNAUTHORIZED()));
        assertEquals(401, handle(router, Request.METHOD_GET, "/a").code);
        assertEquals(Collections.emptyList(), trace);
    }

    @Test
    public void globalMiddlewaresAlsoSeeUnmatchedRequests() throws Exception {
        List<String> trace = new ArrayList<>();
        Router router = new Router(null, Collections.singletonList(recording(trace, "global")));
        Response response = handle(router, Request.METHOD_GET, "/nothing-here");
        assertEquals(404, response.code);
        assertEquals(Collections.singletonList("global"), trace);
    }

    @Test
    public void routeMiddlewaresAreSnapshottedAtRegistration() throws Exception {
        List<String> trace = new ArrayList<>();
        List<Middleware> routeMiddlewares = new ArrayList<>();
        Router router = router();
        router.addRoute("/a", Collections.singleton(Request.METHOD_GET), (c, r) -> StandardResponses.OK("a"), routeMiddlewares);
        routeMiddlewares.add(recording(trace, "late"));
        handle(router, Request.METHOD_GET, "/a");
        assertEquals(Collections.emptyList(), trace);
    }

    @Test
    public void originalPathAndListener() throws Exception {
        Response[] seen = new Response[1];
        int[] calls = {0};
        Router router = new Router((context, request, response) -> {
            calls[0]++;
            seen[0] = response;
        }, Collections.<Middleware>emptyList());
        router.addRoute("/a", Collections.singleton(Request.METHOD_GET), (c, r) -> {
            r.path = "/rewritten";
            return StandardResponses.OK("a");
        });
        RequestContext context = new RequestContext(null);
        Response response = router.handleRequest(context, request(Request.METHOD_GET, "/a"));
        assertEquals("/a", context.data.get(RequestContext.ORIGINAL_PATH));
        assertEquals(1, calls[0]);
        assertSame(response, seen[0]);
        assertNotNull(Router.ORIGINAL_PATH);
        assertEquals(RequestContext.ORIGINAL_PATH, Router.ORIGINAL_PATH);
    }

    private static Middleware recording(List<String> trace, String name) {
        return (context, request, chain) -> {
            trace.add(name);
            return chain.proceed(context, request);
        };
    }
}
