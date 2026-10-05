package com.phlox.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.phlox.server.handlers.router.Router;
import com.phlox.server.handlers.router.middleware.Middleware;
import com.phlox.server.handlers.router.middleware.impl.RedirectsMiddleware;
import com.phlox.server.request.Request;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.testutil.RawHttpClient;
import com.phlox.server.testutil.TestServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Collections;

/**
 * A value that reaches a response header must never carry a line break onto the wire,
 * whoever put it there.
 */
@Timeout(30)
public class SimpleHttpServerHeaderInjectionTest {

    @Test
    public void redirectRuleCannotBeUsedToInjectHeaders() throws Exception {
        RedirectsMiddleware redirects = new RedirectsMiddleware();
        redirects.addRedirectRule(new RedirectsMiddleware.RedirectRule("^/old/(.*)$", "/new/{1}", 302, true, ""));
        Router router = new Router(null, Collections.<Middleware>singletonList(redirects));

        try (TestServer server = new TestServer(router).start();
             RawHttpClient client = server.connect()) {
            //sanity check: the rule does work
            RawHttpClient.Response ok = client.get("/old/page");
            assertEquals(302, ok.code);
            assertEquals("/new/page", ok.header("Location"));
        }

        try (TestServer server = new TestServer(router).start();
             RawHttpClient client = server.connect()) {
            RawHttpClient.Response response = client.get("/old/x%0d%0aX-Injected:%201%0d%0aSet-Cookie:%20a=b");
            if (response != null) {
                assertNull(response.header("X-Injected"), String.join("\n", response.headerLines));
                assertNull(response.header("Set-Cookie"));
                assertFalse(response.code / 100 == 3, "must not redirect to a path with a line break");
            }
        }
    }

    @Test
    public void handlerHeaderWithLineBreakBecomesServerError() throws Exception {
        Router router = new Router(null, Collections.<Middleware>emptyList());
        router.addRoute("/file", Collections.singleton(Request.METHOD_GET), (context, request) -> {
            //e.g. a Content-Disposition built from a file name that contains a line break
            Response response = StandardResponses.OK("secret body");
            response.headers.add("Content-Disposition", "attachment; filename=\"a\r\nX-Injected: 1\"");
            return response;
        });

        try (TestServer server = new TestServer(router).start();
             RawHttpClient client = server.connect()) {
            RawHttpClient.Response response = client.get("/file");
            assertEquals(500, response.code);
            assertNull(response.header("X-Injected"));
            assertNull(response.header("Content-Disposition"));
            assertFalse(response.bodyText().contains("secret body"));
            assertEquals("close", response.header("Connection"));
            assertTrue(client.isClosedByServer());
        }
    }
}
