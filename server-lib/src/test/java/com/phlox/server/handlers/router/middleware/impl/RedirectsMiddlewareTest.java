package com.phlox.server.handlers.router.middleware.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.regex.PatternSyntaxException;

public class RedirectsMiddlewareTest {

    private static Response handle(RedirectsMiddleware middleware, String path) throws Exception {
        Request request = new Request();
        request.method = Request.METHOD_GET;
        request.path = path;
        return middleware.handle(new RequestContext(null), request, (c, r) -> StandardResponses.OK("passed"));
    }

    @Test
    public void groupsAreSubstituted() throws Exception {
        RedirectsMiddleware middleware = new RedirectsMiddleware();
        middleware.addRedirectRule(new RedirectsMiddleware.RedirectRule("^/old/(.*)/(.*)$", "/new/{2}/{1}", 301, true, ""));
        Response response = handle(middleware, "/old/a/b");
        assertEquals(301, response.code);
        assertEquals("/new/b/a", response.headers.get("Location"));
    }

    @Test
    public void unmatchedOptionalGroupBecomesEmpty() throws Exception {
        RedirectsMiddleware middleware = new RedirectsMiddleware();
        middleware.addRedirectRule(new RedirectsMiddleware.RedirectRule("^/x(/y)?$", "/z{1}", 302, true, ""));
        assertEquals("/z", handle(middleware, "/x").headers.get("Location"));
    }

    @Test
    public void wholePathMustMatch() throws Exception {
        RedirectsMiddleware middleware = new RedirectsMiddleware();
        middleware.addRedirectRule(new RedirectsMiddleware.RedirectRule("/old", "/new", 302, true, ""));
        assertEquals(200, handle(middleware, "/old/sub").code);
        assertEquals(302, handle(middleware, "/old").code);
    }

    @Test
    public void disabledRulesAreSkippedAndFirstMatchWins() throws Exception {
        RedirectsMiddleware middleware = new RedirectsMiddleware();
        middleware.setRedirectRules(Arrays.asList(
                new RedirectsMiddleware.RedirectRule("^/a$", "/disabled", 302, false, ""),
                new RedirectsMiddleware.RedirectRule("^/a$", "/first", 307, true, ""),
                new RedirectsMiddleware.RedirectRule("^/a$", "/second", 302, true, "")));
        Response response = handle(middleware, "/a");
        assertEquals(307, response.code);
        assertEquals("/first", response.headers.get("Location"));
    }

    @Test
    public void unmatchedRequestPassesThrough() throws Exception {
        RedirectsMiddleware middleware = new RedirectsMiddleware();
        middleware.addRedirectRule(new RedirectsMiddleware.RedirectRule("^/a$", "/b", 302, true, ""));
        Response response = handle(middleware, "/c");
        assertEquals(200, response.code);
        assertNull(response.headers.get("Location"));
    }

    @Test
    public void assigningTheFromFieldTakesEffect() throws Exception {
        //the Android rule editor assigns the public field directly
        RedirectsMiddleware.RedirectRule rule = new RedirectsMiddleware.RedirectRule();
        rule.from = "^/edited$";
        rule.to = "/target";
        RedirectsMiddleware middleware = new RedirectsMiddleware();
        middleware.addRedirectRule(rule);
        assertEquals("/target", handle(middleware, "/edited").headers.get("Location"));
    }

    @Test
    public void invalidExpressionFailsWhenTheRuleIsCreated() {
        assertThrows(PatternSyntaxException.class,
                () -> new RedirectsMiddleware.RedirectRule("(", "/x", 302, true, ""));
        RedirectsMiddleware.RedirectRule rule = new RedirectsMiddleware.RedirectRule();
        assertThrows(PatternSyntaxException.class, () -> rule.setFrom("["));
    }

    @Test
    public void originalPathIsRecordedWithoutARouter() throws Exception {
        RedirectsMiddleware middleware = new RedirectsMiddleware();
        RequestContext context = new RequestContext(null);
        Request request = new Request();
        request.method = Request.METHOD_GET;
        request.path = "/p";
        middleware.handle(context, request, (c, r) -> StandardResponses.OK("ok"));
        assertEquals("/p", context.data.get(RequestContext.ORIGINAL_PATH));
    }
}
