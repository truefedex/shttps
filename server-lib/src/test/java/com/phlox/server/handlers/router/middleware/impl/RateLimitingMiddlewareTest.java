package com.phlox.server.handlers.router.middleware.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class RateLimitingMiddlewareTest {
    private static final long WINDOW = 60_000;

    private final long[] now = {1_000_000};
    private RateLimitingMiddleware limiter;

    @BeforeEach
    public void setUp() {
        limiter = new RateLimitingMiddleware(3, WINDOW, true);
        limiter.setClock(() -> now[0]);
    }

    @AfterEach
    public void tearDown() {
        limiter.shutdown();
    }

    private static Request request(String peer, String... headers) {
        Request request = new Request();
        request.method = Request.METHOD_GET;
        request.path = "/";
        request.hostAddress = peer;
        for (int i = 0; i < headers.length; i += 2) {
            request.headers.add(headers[i], headers[i + 1]);
        }
        return request;
    }

    private Response handle(Request request) throws Exception {
        return limiter.handle(new RequestContext(null), request,
                (context, r) -> StandardResponses.OK("ok"));
    }

    @Test
    public void allowsUpToTheLimitThenAnswers429() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertEquals(200, handle(request("10.0.0.1")).code);
        }
        Response limited = handle(request("10.0.0.1"));
        assertEquals(429, limited.code);
        assertEquals("60", limited.headers.get("Retry-After"));
        assertEquals("0", limited.headers.get("X-RateLimit-Remaining"));
    }

    @Test
    public void reportsRemainingRequests() throws Exception {
        assertEquals("2", handle(request("10.0.0.1")).headers.get("rate_limit_remaining"));
        assertEquals("1", handle(request("10.0.0.1")).headers.get("rate_limit_remaining"));
        assertEquals(Long.toString(now[0] + WINDOW), handle(request("10.0.0.1")).headers.get("rate_limit_reset"));
    }

    @Test
    public void windowResetsAfterItExpires() throws Exception {
        for (int i = 0; i < 4; i++) {
            handle(request("10.0.0.1"));
        }
        assertEquals(429, handle(request("10.0.0.1")).code);
        now[0] += WINDOW + 1;
        assertEquals(200, handle(request("10.0.0.1")).code);
    }

    @Test
    public void clientsAreCountedSeparately() throws Exception {
        for (int i = 0; i < 3; i++) {
            handle(request("10.0.0.1"));
        }
        assertEquals(429, handle(request("10.0.0.1")).code);
        assertEquals(200, handle(request("10.0.0.2")).code);
    }

    @Test
    public void disabledLimiterPassesEverything() throws Exception {
        limiter.setEnabled(false);
        for (int i = 0; i < 10; i++) {
            assertEquals(200, handle(request("10.0.0.1")).code);
        }
    }

    @Test
    public void nullResponsePassesThrough() throws Exception {
        assertNull(limiter.handle(new RequestContext(null), request("10.0.0.1"), (context, r) -> null));
    }

    @Test
    public void forwardedHeadersFromAnUntrustedPeerAreIgnored() throws Exception {
        //the bypass a naive fix would open: a fresh bucket per spoofed header value
        for (int i = 0; i < 3; i++) {
            handle(request("10.0.0.1", "x-forwarded-for", "192.0.2." + i));
        }
        assertEquals(429, handle(request("10.0.0.1", "x-forwarded-for", "192.0.2.99")).code);
        assertEquals("10.0.0.1", limiter.getClientIdentifier(request("10.0.0.1", "x-real-ip", "192.0.2.5")));
    }

    @Test
    public void loopbackProxyIsTrustedByDefault() {
        assertEquals("192.0.2.7", limiter.getClientIdentifier(request("127.0.0.1", "x-forwarded-for", "192.0.2.7")));
        assertEquals("192.0.2.8", limiter.getClientIdentifier(request("::1", "x-real-ip", "192.0.2.8")));
        assertEquals("192.0.2.9", limiter.getClientIdentifier(request("127.0.0.1", "x-client-ip", "192.0.2.9")));
    }

    @Test
    public void clientsBehindAProxyAreCountedSeparately() throws Exception {
        //the bug this fixes: header names were looked up in mixed case, request headers are
        //lower-cased, so everyone behind a proxy shared the proxy's bucket
        for (int i = 0; i < 3; i++) {
            handle(request("127.0.0.1", "x-forwarded-for", "192.0.2.1"));
        }
        assertEquals(429, handle(request("127.0.0.1", "x-forwarded-for", "192.0.2.1")).code);
        assertEquals(200, handle(request("127.0.0.1", "x-forwarded-for", "192.0.2.2")).code);
    }

    @Test
    public void headersAreIgnoredWhenTrustIsOff() {
        limiter.setTrustToIPHeaders(false);
        assertEquals("127.0.0.1", limiter.getClientIdentifier(request("127.0.0.1", "x-forwarded-for", "192.0.2.7")));
    }

    @Test
    public void spoofedLeftmostEntryIsNotBelieved() {
        //the client sent "X-Forwarded-For: 6.6.6.6", the proxy appended the address it saw
        assertEquals("192.0.2.7", limiter.getClientIdentifier(
                request("127.0.0.1", "x-forwarded-for", "6.6.6.6, 192.0.2.7")));
    }

    @Test
    public void configuredProxiesReplaceTheLoopbackDefault() {
        limiter.addTrustedProxy("10.0.0.10");
        limiter.addTrustedProxy("10.0.0.11");
        //a chain of two trusted proxies: skip both, take the hop before them
        assertEquals("192.0.2.7", limiter.getClientIdentifier(
                request("10.0.0.10", "x-forwarded-for", "6.6.6.6, 192.0.2.7, 10.0.0.11")));
        //loopback is no longer trusted once proxies are configured explicitly
        assertEquals("127.0.0.1", limiter.getClientIdentifier(request("127.0.0.1", "x-forwarded-for", "192.0.2.7")));
    }

    @Test
    public void invalidForwardedValuesFallBackToThePeer() {
        assertEquals("127.0.0.1", limiter.getClientIdentifier(request("127.0.0.1", "x-forwarded-for", "not-an-ip")));
        assertEquals("127.0.0.1", limiter.getClientIdentifier(request("127.0.0.1", "x-real-ip", "evil.example")));
        assertEquals("2001:db8::1", limiter.getClientIdentifier(request("127.0.0.1", "x-forwarded-for", "2001:db8::1")));
    }

    @Test
    public void cleanupDropsOnlyExpiredWindows() throws Exception {
        handle(request("10.0.0.1"));
        now[0] += WINDOW / 2;
        handle(request("10.0.0.2"));
        now[0] += WINDOW / 2 + 1;
        limiter.cleanupExpiredEntries();
        assertEquals(1, limiter.getActiveClients());
    }

    @Test
    public void concurrentRequestsAreCountedExactly() throws Exception {
        RateLimitingMiddleware wide = new RateLimitingMiddleware(500, WINDOW, false);
        wide.setClock(() -> now[0]);
        AtomicInteger allowed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch done = new CountDownLatch(1000);
        try {
            for (int i = 0; i < 1000; i++) {
                pool.execute(() -> {
                    try {
                        Response response = wide.handle(new RequestContext(null), request("10.0.0.1"),
                                (context, r) -> StandardResponses.OK("ok"));
                        if (response.code == 200) {
                            allowed.incrementAndGet();
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertNotNull(done.await(30, TimeUnit.SECONDS) ? "" : null);
            assertEquals(500, allowed.get());
        } finally {
            pool.shutdownNow();
            wide.shutdown();
        }
    }
}
