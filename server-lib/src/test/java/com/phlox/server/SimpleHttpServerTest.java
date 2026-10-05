package com.phlox.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.phlox.server.handlers.router.Router;
import com.phlox.server.handlers.router.middleware.Middleware;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.testutil.CallbackAdapter;
import com.phlox.server.testutil.RawHttpClient;
import com.phlox.server.testutil.TestServer;
import com.phlox.server.utils.MultiMap;
import com.phlox.server.utils.Utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The connection loop of {@link SimpleHttpServer} over a real socket: keep-alive, framing of
 * responses, unread bodies, 100-continue, connection filtering and the callbacks.
 */
@Timeout(30)
public class SimpleHttpServerTest {

    private static Router router() {
        Router router = new Router(null, Collections.<Middleware>emptyList());
        router.addRoute("/", new HashSet<>(Arrays.asList(Request.METHOD_GET, Request.METHOD_HEAD)),
                (context, request) -> Request.METHOD_HEAD.equals(request.method) ?
                        new Response("text/plain", 5, null) : StandardResponses.OK("hello"));
        //a handler that never looks at the body
        router.addRoute("/ignore-body", Collections.singleton(Request.METHOD_POST),
                (context, request) -> StandardResponses.OK("ignored"));
        router.addRoute("/echo", Collections.singleton(Request.METHOD_POST), (context, request) -> {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            Utils.copyStream(request.bodyStream, body);
            return StandardResponses.OK(body.toString("UTF-8"));
        });
        router.addRoute("/reject", Collections.singleton(Request.METHOD_POST),
                (context, request) -> StandardResponses.UNAUTHORIZED("no"));
        router.addRoute("/stream", Collections.singleton(Request.METHOD_GET),
                (context, request) -> new Response(new ByteArrayInputStream("streamed body".getBytes(StandardCharsets.UTF_8))));
        return router;
    }

    @Test
    public void keepAliveServesSeveralRequestsOnOneConnection() throws Exception {
        try (TestServer server = new TestServer(router()).start();
             RawHttpClient client = server.connect()) {
            for (int i = 0; i < 3; i++) {
                RawHttpClient.Response response = client.get("/");
                assertEquals(200, response.code);
                assertEquals("hello", response.bodyText());
                assertEquals("5", response.header("Content-Length"));
                assertEquals("Keep-Alive", response.header("Connection"));
                assertEquals("timeout=30", response.header("Keep-Alive"));
                assertEquals(SimpleHttpServer.SERVER_NAME, response.header("Server"));
            }
        }
    }

    @Test
    public void connectionCloseIsHonoured() throws Exception {
        try (TestServer server = new TestServer(router()).start();
             RawHttpClient client = server.connect()) {
            client.send("GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
            RawHttpClient.Response response = client.readResponse();
            assertEquals(200, response.code);
            assertEquals("close", response.header("Connection"));
            assertTrue(client.isClosedByServer());
        }
    }

    @Test
    public void keepAliveTimeoutOfZeroDisablesKeepAlive() throws Exception {
        TestServer server = new TestServer(router());
        server.server.connectionKeepAliveTimeoutSeconds = 0;
        try (TestServer ignored = server.start();
             RawHttpClient client = server.connect()) {
            assertEquals("close", client.get("/").header("Connection"));
            assertTrue(client.isClosedByServer());
        }
    }

    @Test
    public void headResponseCarriesNoBody() throws Exception {
        try (TestServer server = new TestServer(router()).start();
             RawHttpClient client = server.connect()) {
            client.send("HEAD / HTTP/1.1\r\nHost: x\r\n\r\n");
            RawHttpClient.Response head = client.readResponse(true);
            assertEquals(200, head.code);
            assertEquals("5", head.header("Content-Length"));
            //if any body bytes had been sent they would now be read as the next status line
            assertEquals("hello", client.get("/").bodyText());
        }
    }

    @Test
    public void unreadSmallBodyIsDrainedAndTheConnectionReused() throws Exception {
        try (TestServer server = new TestServer(router()).start();
             RawHttpClient client = server.connect()) {
            char[] body = new char[1000];
            Arrays.fill(body, 'b');
            client.send("POST /ignore-body HTTP/1.1\r\nHost: x\r\nContent-Length: 1000\r\n\r\n" + new String(body));
            RawHttpClient.Response response = client.readResponse();
            assertEquals("ignored", response.bodyText());
            assertEquals("Keep-Alive", response.header("Connection"));
            assertEquals("hello", client.get("/").bodyText());
        }
    }

    @Test
    public void unreadBodyThatDoesNotArriveClosesTheConnection() throws Exception {
        try (TestServer server = new TestServer(router()).start();
             RawHttpClient client = server.connect()) {
            //promises a body it never sends: draining it times out, so the connection can not be reused
            client.send("POST /ignore-body HTTP/1.1\r\nHost: x\r\nContent-Length: 200000\r\n\r\npartial");
            RawHttpClient.Response response = client.readResponse();
            assertEquals(200, response.code);
            assertEquals("close", response.header("Connection"));
        }
    }

    @Test
    public void expectContinueIsSentWhenTheHandlerReadsTheBody() throws Exception {
        try (TestServer server = new TestServer(router()).start();
             RawHttpClient client = server.connect()) {
            client.send("POST /echo HTTP/1.1\r\nHost: x\r\nContent-Length: 4\r\nExpect: 100-continue\r\n\r\n");
            assertEquals("HTTP/1.1 100 Continue", client.readLine());
            assertEquals("", client.readLine());
            client.send("ping");
            RawHttpClient.Response response = client.readResponse();
            assertEquals(200, response.code);
            assertEquals("ping", response.bodyText());
        }
    }

    @Test
    public void expectContinueIsNotSentWhenTheHandlerRejectsEarly() throws Exception {
        try (TestServer server = new TestServer(router()).start();
             RawHttpClient client = server.connect()) {
            client.send("POST /reject HTTP/1.1\r\nHost: x\r\nContent-Length: 4\r\nExpect: 100-continue\r\n\r\n");
            RawHttpClient.Response response = client.readResponse();
            //the final answer comes first, and the body the client still holds makes the connection unusable
            assertEquals(401, response.code);
            assertEquals("close", response.header("Connection"));
        }
    }

    @Test
    public void bodyOfUnknownLengthIsDelimitedByClosing() throws Exception {
        try (TestServer server = new TestServer(router()).start();
             RawHttpClient client = server.connect()) {
            RawHttpClient.Response response = client.get("/stream");
            assertNull(response.header("Content-Length"));
            assertEquals("close", response.header("Connection"));
            assertEquals("streamed body", response.bodyText());
        }
    }

    @Test
    public void additionalResponseHeadersAreAdded() throws Exception {
        TestServer server = new TestServer(router());
        MultiMap<String, String> extra = new MultiMap<>();
        extra.add("X-Frame-Options", "DENY");
        extra.add("X-Multi", "a");
        extra.add("X-Multi", "b");
        server.server.additionalResponseHeaders = extra;
        try (TestServer ignored = server.start();
             RawHttpClient client = server.connect()) {
            RawHttpClient.Response response = client.get("/");
            assertEquals("DENY", response.header("X-Frame-Options"));
            assertEquals(Arrays.asList("a", "b"), response.headers("X-Multi"));
        }
    }

    @Test
    public void hostNameCheckAcceptsTheHostWithAnyPort() throws Exception {
        TestServer server = new TestServer(router());
        server.server.hostName = "example.com";
        try (TestServer ignored = server.start()) {
            try (RawHttpClient client = server.connect()) {
                client.send("GET / HTTP/1.1\r\nHost: EXAMPLE.com:8080\r\n\r\n");
                assertEquals(200, client.readResponse().code);
            }
            try (RawHttpClient client = server.connect()) {
                client.send("GET / HTTP/1.1\r\nHost: evil.example\r\n\r\n");
                assertNull(client.readResponse());
            }
            try (RawHttpClient client = server.connect()) {
                client.send("GET / HTTP/1.1\r\n\r\n");
                assertNull(client.readResponse());
            }
        }
    }

    @Test
    public void connectionsFromOtherAddressesAreRejected() throws Exception {
        CountDownLatch rejected = new CountDownLatch(1);
        int[] reason = {0};
        TestServer server = new TestServer(router(), new CallbackAdapter() {
            @Override
            public void onConnectionRejected(Socket socket, int r, long connectionId) {
                reason[0] = r;
                rejected.countDown();
            }
        });
        server.server.allowedClientAddresses = Collections.singleton(InetAddress.getByName("192.0.2.1"));
        try (TestServer ignored = server.start();
             RawHttpClient client = server.connect()) {
            assertTrue(client.isClosedByServer());
            assertTrue(rejected.await(5, TimeUnit.SECONDS));
            assertEquals(SimpleHttpServer.REASON_CLIENT_ADDRESS_NOT_ALLOWED, reason[0]);
        }
    }

    @Test
    public void allowedAddressIsServed() throws Exception {
        TestServer server = new TestServer(router());
        server.server.allowedClientAddresses = Collections.singleton(InetAddress.getLoopbackAddress());
        try (TestServer ignored = server.start();
             RawHttpClient client = server.connect()) {
            assertEquals(200, client.get("/").code);
        }
    }

    @Test
    public void callbacksArriveInOrder() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch closed = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        TestServer server = new TestServer(router(), new CallbackAdapter() {
            @Override public void onServerStarted() { events.add("started"); }
            @Override public void onServerStopped() { events.add("stopped"); stopped.countDown(); }
            @Override public void onNewConnection(Socket socket, long id) { events.add("new"); }
            @Override public void onConnectionTracked(Socket socket, long id) { events.add("tracked"); }
            @Override public void onConnectionRequest(RequestContext context, Request request) {
                events.add("request " + request.path);
            }
            @Override public void onConnectionResponse(RequestContext context, Request request, Response response) {
                events.add("response " + response.code);
            }
            @Override public void onConnectionClosed(Socket socket, String reason, long id) {
                events.add("closed");
                closed.countDown();
            }
        });
        server.start();
        try (RawHttpClient client = server.connect()) {
            assertEquals(200, client.get("/").code);
        }
        assertTrue(closed.await(10, TimeUnit.SECONDS));
        server.close();
        assertTrue(stopped.await(10, TimeUnit.SECONDS));
        assertEquals(Arrays.asList("started", "new", "tracked", "request /", "response 200", "closed", "stopped"), events);
    }

    @Test
    public void openConnectionsAreTrackedAndClosedOnStop() throws Exception {
        TestServer server = new TestServer(router()).start();
        RawHttpClient client = server.connect();
        try {
            assertEquals(200, client.get("/").code);
            assertTrue(server.server.hasOpenConnections());
            assertTrue(server.server.isListenThreadRunning());
            server.close();
            assertFalse(server.server.isListenThreadRunning());
            assertTrue(client.isClosedByServer());
        } finally {
            client.close();
        }
    }

    @Test
    public void notFoundForUnknownRoute() throws Exception {
        try (TestServer server = new TestServer(router()).start();
             RawHttpClient client = server.connect()) {
            RawHttpClient.Response response = client.get("/missing");
            assertEquals(404, response.code);
            assertNotNull(response.header("Content-Length"));
            //a 404 does not cost the connection
            assertEquals(200, client.get("/").code);
        }
    }

    @Test
    public void stoppedServerCanBeStartedAgain() throws Exception {
        TestServer server = new TestServer(router());
        server.start();
        try (RawHttpClient client = server.connect()) {
            assertEquals(200, client.get("/").code);
        }
        server.close();
        //the same instance on a new socket: the stop flag used to stay set, so the loop exited at once
        server.start();
        try (RawHttpClient client = server.connect()) {
            assertEquals(200, client.get("/").code);
        } finally {
            server.close();
        }
    }

    @Test
    public void stopWithoutStartDoesNothing() {
        new SimpleHttpServer(router(), null).stopListen();
    }

    @Test
    public void hostHeaderMatching() {
        assertTrue(SimpleHttpServer.isRequestForHost("example.com", "example.com"));
        assertTrue(SimpleHttpServer.isRequestForHost("example.com", "Example.COM:8443"));
        assertTrue(SimpleHttpServer.isRequestForHost("::1", "[::1]:8080"));
        assertTrue(SimpleHttpServer.isRequestForHost("[::1]", "[::1]"));
        assertTrue(SimpleHttpServer.isRequestForHost("fe80::1", "[FE80::1]"));
        assertFalse(SimpleHttpServer.isRequestForHost("example.com", "example.com.evil.org"));
        assertFalse(SimpleHttpServer.isRequestForHost("example.com", null));
        assertFalse(SimpleHttpServer.isRequestForHost("::1", "[::1"));
        assertFalse(SimpleHttpServer.isRequestForHost("::1", "::1"));
    }

    @Test
    public void handlerCanAskToCloseTheConnection() throws Exception {
        Router router = router();
        router.addRoute("/bye", Collections.singleton(Request.METHOD_GET), (context, request) -> {
            Response response = StandardResponses.OK("bye");
            response.headers.add(Response.HEADER_CONNECTION, "close");
            return response;
        });
        try (TestServer server = new TestServer(router).start();
             RawHttpClient client = server.connect()) {
            RawHttpClient.Response response = client.get("/bye");
            assertEquals("bye", response.bodyText());
            assertEquals(Collections.singletonList("close"), response.headers("Connection"));
            assertTrue(client.isClosedByServer());
        }
    }

    @Test
    public void closingConnectionOverridesTheHandlersConnectionHeader() throws Exception {
        Router router = router();
        router.addRoute("/stream-keep", Collections.singleton(Request.METHOD_GET), (context, request) -> {
            //a body of unknown length can only end by closing, whatever the handler claims
            Response response = new Response(new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)));
            response.headers.add(Response.HEADER_CONNECTION, "keep-alive");
            return response;
        });
        try (TestServer server = new TestServer(router).start();
             RawHttpClient client = server.connect()) {
            RawHttpClient.Response response = client.get("/stream-keep");
            assertEquals(Collections.singletonList("close"), response.headers("Connection"));
            assertNull(response.header("Keep-Alive"));
            assertEquals("abc", response.bodyText());
        }
    }

    @Test
    public void headerSetInLowerCaseIsNotDuplicated() throws Exception {
        Router router = router();
        router.addRoute("/lower", Collections.singleton(Request.METHOD_GET), (context, request) -> {
            Response response = new Response(new ByteArrayInputStream("ok".getBytes(StandardCharsets.UTF_8)));
            response.headers.add("content-length", "2");
            response.headers.add("connection", "close");
            return response;
        });
        try (TestServer server = new TestServer(router).start();
             RawHttpClient client = server.connect()) {
            RawHttpClient.Response response = client.get("/lower");
            //the server used to miss both and add its own: two Content-Length headers, and a
            //keep-alive connection the handler asked to close
            assertEquals(Collections.singletonList("2"), response.headers("Content-Length"));
            assertEquals(Collections.singletonList("close"), response.headers("Connection"));
            assertEquals("ok", response.bodyText());
            assertTrue(client.isClosedByServer());
        }
    }
}
