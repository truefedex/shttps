package com.phlox.server.handlers.router.middleware.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.MultiMap;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

public class CustomHeadersMiddlewareTest {

    private static MultiMap<String, String> headers(String... nameValues) {
        MultiMap<String, String> map = new MultiMap<>();
        for (int i = 0; i < nameValues.length; i += 2) {
            map.add(nameValues[i], nameValues[i + 1]);
        }
        return map;
    }

    private static CustomHeadersMiddleware.Rule rule(String path, CustomHeadersMiddleware.IfHeadersExist mode,
                                                     MultiMap<String, String> headers) {
        return new CustomHeadersMiddleware.Rule(path, headers, mode, null, null, null);
    }

    private static Response handle(CustomHeadersMiddleware middleware, String method, String path,
                                   Response handlerResponse) throws Exception {
        Request request = new Request();
        request.method = method;
        request.path = path;
        return middleware.handle(new RequestContext(null), request, (c, r) -> handlerResponse);
    }

    private static Response okWith(String name, String value) {
        Response response = StandardResponses.OK("ok");
        response.headers.add(name, value);
        return response;
    }

    @Test
    public void addsHeadersUnderThePrefix() throws Exception {
        CustomHeadersMiddleware middleware = new CustomHeadersMiddleware(Collections.singletonList(
                rule("/static/", CustomHeadersMiddleware.IfHeadersExist.OVERRIDE, headers("Cache-Control", "max-age=60"))));
        assertEquals("max-age=60", handle(middleware, "GET", "/static/app.js", StandardResponses.OK("x")).headers.get("Cache-Control"));
        assertNull(handle(middleware, "GET", "/api/x", StandardResponses.OK("x")).headers.get("Cache-Control"));
    }

    @Test
    public void multipleValuesAreJoined() throws Exception {
        CustomHeadersMiddleware middleware = new CustomHeadersMiddleware(Collections.singletonList(
                rule("/", CustomHeadersMiddleware.IfHeadersExist.OVERRIDE, headers("X-A", "1", "X-A", "2"))));
        assertEquals(Collections.singletonList("1, 2"), handle(middleware, "GET", "/", StandardResponses.OK("x")).headers.getAll("X-A"));
    }

    @Test
    public void overrideReplacesEvenWhenSpelledDifferently() throws Exception {
        CustomHeadersMiddleware middleware = new CustomHeadersMiddleware(Collections.singletonList(
                rule("/", CustomHeadersMiddleware.IfHeadersExist.OVERRIDE, headers("content-type", "text/csv"))));
        Response response = handle(middleware, "GET", "/", okWith("Content-Type", "text/html"));
        assertEquals(Collections.singletonList("text/csv"), response.headers.getAll("Content-Type"));
    }

    @Test
    public void appendKeepsTheExistingValue() throws Exception {
        CustomHeadersMiddleware middleware = new CustomHeadersMiddleware(Collections.singletonList(
                rule("/", CustomHeadersMiddleware.IfHeadersExist.APPEND, headers("Vary", "Accept"))));
        assertEquals(Arrays.asList("Origin", "Accept"), handle(middleware, "GET", "/", okWith("Vary", "Origin")).headers.getAll("Vary"));
    }

    @Test
    public void ignoreLeavesTheExistingValue() throws Exception {
        CustomHeadersMiddleware middleware = new CustomHeadersMiddleware(Collections.singletonList(
                rule("/", CustomHeadersMiddleware.IfHeadersExist.IGNORE, headers("X-Frame-Options", "DENY"))));
        assertEquals(Collections.singletonList("SAMEORIGIN"),
                handle(middleware, "GET", "/", okWith("X-Frame-Options", "SAMEORIGIN")).headers.getAll("X-Frame-Options"));
        assertEquals("DENY", handle(middleware, "GET", "/", StandardResponses.OK("x")).headers.get("X-Frame-Options"));
    }

    @Test
    public void filtersByMethodStatusAndPostfix() throws Exception {
        CustomHeadersMiddleware.Rule rule = new CustomHeadersMiddleware.Rule("/", headers("X-Filtered", "yes"),
                CustomHeadersMiddleware.IfHeadersExist.OVERRIDE,
                new HashSet<>(Collections.singletonList("GET")),
                new HashSet<>(Collections.singletonList(200)),
                new HashSet<>(Arrays.asList(".js", ".css")));
        CustomHeadersMiddleware middleware = new CustomHeadersMiddleware(Collections.singletonList(rule));
        assertEquals("yes", handle(middleware, "GET", "/app.js", StandardResponses.OK("x")).headers.get("X-Filtered"));
        assertNull(handle(middleware, "POST", "/app.js", StandardResponses.OK("x")).headers.get("X-Filtered"));
        assertNull(handle(middleware, "GET", "/app.js", StandardResponses.NOT_FOUND()).headers.get("X-Filtered"));
        assertNull(handle(middleware, "GET", "/index.html", StandardResponses.OK("x")).headers.get("X-Filtered"));
    }

    @Test
    public void nestedPrefixesApplyShortestFirst() throws Exception {
        List<CustomHeadersMiddleware.Rule> rules = Arrays.asList(
                rule("/a/b/", CustomHeadersMiddleware.IfHeadersExist.OVERRIDE, headers("X-Level", "deep")),
                rule("/a/", CustomHeadersMiddleware.IfHeadersExist.OVERRIDE, headers("X-Level", "shallow")));
        CustomHeadersMiddleware middleware = new CustomHeadersMiddleware(rules);
        //the longer, more specific prefix runs last and wins
        assertEquals(Collections.singletonList("deep"),
                handle(middleware, "GET", "/a/b/c", StandardResponses.OK("x")).headers.getAll("X-Level"));
        assertEquals("shallow", handle(middleware, "GET", "/a/x", StandardResponses.OK("x")).headers.get("X-Level"));
    }

    @Test
    public void nullResponsePassesThrough() throws Exception {
        CustomHeadersMiddleware middleware = new CustomHeadersMiddleware(Collections.singletonList(
                rule("/", CustomHeadersMiddleware.IfHeadersExist.OVERRIDE, headers("X-A", "1"))));
        assertNull(handle(middleware, "GET", "/", null));
    }
}
