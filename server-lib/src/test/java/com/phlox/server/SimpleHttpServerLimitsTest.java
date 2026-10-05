package com.phlox.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.phlox.server.handlers.router.Router;
import com.phlox.server.handlers.router.middleware.Middleware;
import com.phlox.server.request.DefaultRequestHeadersParser;
import com.phlox.server.request.Request;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.testutil.CallbackAdapter;
import com.phlox.server.testutil.RawHttpClient;
import com.phlox.server.testutil.TestServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Timeout(30)
public class SimpleHttpServerLimitsTest {

    private static Router okRouter() {
        Router router = new Router(null, Collections.<Middleware>emptyList());
        router.addRoute("/", Collections.singleton(Request.METHOD_GET), (context, request) -> StandardResponses.OK("ok"));
        return router;
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    @Test
    public void oversizedHeaderIsAnsweredWith431() throws Exception {
        try (TestServer server = new TestServer(okRouter()).start();
             RawHttpClient client = server.connect()) {
            client.send("GET / HTTP/1.1\r\nHost: localhost\r\nCookie: " +
                    repeat('a', DefaultRequestHeadersParser.DEFAULT_MAX_LINE_LENGTH + 4000) + "\r\n\r\n");
            RawHttpClient.Response response = client.readResponse();
            assertNotNull(response, "the client must get an answer, not a dropped connection");
            assertEquals(431, response.code);
            assertEquals("close", response.header("Connection"));
            assertTrue(client.isClosedByServer());
        }
    }

    @Test
    public void overlongRequestLineIsAnsweredWith414() throws Exception {
        try (TestServer server = new TestServer(okRouter()).start();
             RawHttpClient client = server.connect()) {
            client.send("GET /" + repeat('a', DefaultRequestHeadersParser.DEFAULT_MAX_REQUEST_LINE_LENGTH) + " HTTP/1.1\r\n\r\n");
            RawHttpClient.Response response = client.readResponse();
            assertNotNull(response);
            assertEquals(414, response.code);
        }
    }

    @Test
    public void tricklingHeadIsCutOff() throws Exception {
        TestServer server = new TestServer(okRouter());
        server.server.requestHeadersParser.headReadTimeoutMillis = 300;
        try (TestServer ignored = server.start();
             RawHttpClient client = server.connect()) {
            client.send("GET / HTTP/1.1\r\n");
            boolean cutOff = false;
            for (int i = 0; i < 50 && !cutOff; i++) {
                try {
                    client.send("X");
                } catch (java.io.IOException e) {
                    cutOff = true;
                }
                Thread.sleep(50);
            }
            client.setReadTimeout(2000);
            assertTrue(cutOff || client.isClosedByServer(), "a head trickled in for 2.5 s must not keep the connection");
        }
    }

    @Test
    public void connectionsBeyondTheLimitAreRejected() throws Exception {
        CountDownLatch rejected = new CountDownLatch(1);
        int[] reason = {0};
        TestServer server = new TestServer(okRouter(), new CallbackAdapter() {
            @Override
            public void onConnectionRejected(java.net.Socket socket, int r, long connectionId) {
                reason[0] = r;
                rejected.countDown();
            }
        });
        server.server.maxConnections = 1;
        try (TestServer ignored = server.start()) {
            try (RawHttpClient first = server.connect()) {
                //the first connection is served and kept alive
                assertEquals(200, first.get("/").code);

                try (RawHttpClient second = server.connect()) {
                    assertTrue(second.isClosedByServer());
                }
                assertTrue(rejected.await(5, TimeUnit.SECONDS));
                assertEquals(SimpleHttpServer.REASON_TOO_MANY_CONNECTIONS, reason[0]);
            }

            //once the first one is gone, a new connection is served again
            RawHttpClient.Response response = null;
            for (int attempt = 0; attempt < 50 && response == null; attempt++) {
                try (RawHttpClient third = server.connect()) {
                    response = third.get("/");
                } catch (java.io.IOException e) {
                    //the server may not have noticed the first close yet
                }
                if (response == null) {
                    Thread.sleep(100);
                }
            }
            assertNotNull(response);
            assertEquals(200, response.code);
        }
    }
}
