package com.phlox.server.handlers.router.middleware.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.phlox.server.request.DefaultRequestBodyReader;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Which rule governs an origin is now answered in one place, {@link CORSMiddleware#findRuleForOrigin},
 * because the WebSocket endpoints ask the same question at their handshake - a browser applies no
 * CORS to a handshake, so those endpoints have to enforce the configured origins themselves. These
 * tests pin the matching itself and the headers the middleware puts on an ordinary response, so the
 * two paths cannot drift apart.
 */
public class CORSMiddlewareTest {

    @Test
    public void namedOriginWins() {
        CORSMiddleware.CORSRule trusted = rule("http://trusted.example");
        CORSMiddleware.CORSRule wildcard = rule("*");
        List<CORSMiddleware.CORSRule> rules = Arrays.asList(wildcard, trusted);

        //the exact rule is chosen even though the catch-all comes first in the list
        assertSame(trusted, CORSMiddleware.findRuleForOrigin(rules, "http://trusted.example"));
    }

    @Test
    public void anyOtherOriginFallsBackToTheWildcard() {
        CORSMiddleware.CORSRule wildcard = rule("*");
        List<CORSMiddleware.CORSRule> rules = Arrays.asList(rule("http://trusted.example"), wildcard);

        assertSame(wildcard, CORSMiddleware.findRuleForOrigin(rules, "http://anywhere.example"));
    }

    @Test
    public void withoutAMatchingRuleThereIsNoRule() {
        List<CORSMiddleware.CORSRule> rules = new ArrayList<>();
        rules.add(rule("http://trusted.example"));

        assertNull(CORSMiddleware.findRuleForOrigin(rules, "http://evil.example"));
        assertNull(CORSMiddleware.findRuleForOrigin(rules, null));
        assertNull(CORSMiddleware.findRuleForOrigin(null, "http://trusted.example"));
        //matching is exact, as it always was - a different port is a different origin
        assertNull(CORSMiddleware.findRuleForOrigin(rules, "http://trusted.example:8080"));
    }

    @Test
    public void allowedOriginIsEchoedOnAnOrdinaryResponse() throws Exception {
        CORSMiddleware middleware = new CORSMiddleware(Arrays.asList(rule("http://trusted.example")));

        Response response = handle(middleware, get("http://trusted.example"));

        assertEquals("http://trusted.example",
                response.headers.get(Response.HEADER_ACCESS_CONTROL_ALLOW_ORIGIN));
        //the answer depends on the request's Origin, so it must not be cached for every origin
        assertEquals(Response.HEADER_ORIGIN, response.headers.get(Response.HEADER_VARY));
    }

    @Test
    public void wildcardRuleAnswersWithAStar() throws Exception {
        CORSMiddleware middleware = new CORSMiddleware(Arrays.asList(rule("*")));

        Response response = handle(middleware, get("http://anywhere.example"));

        assertEquals("*", response.headers.get(Response.HEADER_ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    public void unknownOriginGetsNoAllowOriginHeader() throws Exception {
        CORSMiddleware middleware = new CORSMiddleware(Arrays.asList(rule("http://trusted.example")));

        Response response = handle(middleware, get("http://evil.example"));

        //the request is still served; it is the browser that withholds the result from the page
        assertEquals(200, response.code);
        assertNull(response.headers.get(Response.HEADER_ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    public void preflightIsAnsweredWithoutReachingTheHandler() throws Exception {
        CORSMiddleware.CORSRule trusted = rule("http://trusted.example");
        trusted.allowMethods = new String[]{"GET", "POST"};
        trusted.allowCredentials = Boolean.TRUE;
        trusted.maxAge = 600;
        CORSMiddleware middleware = new CORSMiddleware(Arrays.asList(trusted));

        Request request = get("http://trusted.example");
        request.method = Request.METHOD_OPTIONS;
        request.headers.put(Request.HEADER_ACCESS_CONTROL_REQUEST_METHOD, "POST");

        boolean[] handlerRan = {false};
        Response response = middleware.handle(context(), request, (c, r) -> {
            handlerRan[0] = true;
            return StandardResponses.OK("ok");
        });

        assertNotNull(response);
        assertEquals(204, response.code);
        assertEquals("GET, POST", response.headers.get(Response.HEADER_ACCESS_CONTROL_ALLOW_METHODS));
        assertEquals("true", response.headers.get(Response.HEADER_ACCESS_CONTROL_ALLOW_CREDENTIALS));
        assertEquals("600", response.headers.get(Response.HEADER_ACCESS_CONTROL_MAX_AGE));
        assertEquals(false, handlerRan[0], "a preflight must be answered by the middleware alone");
    }

    private static CORSMiddleware.CORSRule rule(String origin) {
        CORSMiddleware.CORSRule rule = new CORSMiddleware.CORSRule();
        rule.origin = origin;
        return rule;
    }

    private static Request get(String origin) {
        Request request = new Request();
        request.method = Request.METHOD_GET;
        request.path = "/anything";
        request.headers.put(Request.HEADER_HOST, "localhost");
        if (origin != null) {
            request.headers.put(Request.HEADER_ORIGIN, origin);
        }
        return request;
    }

    private static RequestContext context() {
        return new RequestContext(new DefaultRequestBodyReader());
    }

    private static Response handle(CORSMiddleware middleware, Request request) throws Exception {
        return middleware.handle(context(), request, (c, r) -> StandardResponses.OK("ok"));
    }
}
