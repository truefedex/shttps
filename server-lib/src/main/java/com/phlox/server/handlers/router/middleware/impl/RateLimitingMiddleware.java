package com.phlox.server.handlers.router.middleware.impl;

import com.phlox.server.handlers.router.middleware.HandlerExecutionChain;
import com.phlox.server.handlers.router.middleware.Middleware;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.TextResponse;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

public class RateLimitingMiddleware implements Middleware {
    
    // Configuration constants
    private static final int DEFAULT_MAX_REQUESTS = 100;
    private static final long DEFAULT_TIME_WINDOW_MS = 60000; // 1 minute
    private static final long CLEANUP_INTERVAL_MS = 300000; // 5 minutes
    
    // Rate limit tracking
    private final ConcurrentHashMap<String, RateLimitEntry> rateLimitMap = new ConcurrentHashMap<>();
    private volatile ScheduledExecutorService cleanupExecutor;
    private final Object executorLock = new Object();
    
    // Configuration
    private volatile int maxRequests = DEFAULT_MAX_REQUESTS;
    private volatile long timeWindowMs = DEFAULT_TIME_WINDOW_MS;
    private volatile boolean enabled = true;
    
    // Trusted proxy configuration
    private final Set<String> trustedProxies = ConcurrentHashMap.newKeySet();
    private volatile boolean trustToIPHeaders = true;

    private volatile LongSupplier clock = System::currentTimeMillis;

    //request headers are lower-cased by the request parser
    static final String HEADER_X_FORWARDED_FOR = "x-forwarded-for";
    static final String HEADER_X_REAL_IP = "x-real-ip";
    static final String HEADER_X_CLIENT_IP = "x-client-ip";

    // Rate limit entry for tracking requests; only touched inside ConcurrentHashMap.compute
    private static class RateLimitEntry {
        int requestCount = 0;
        final long windowStart;

        RateLimitEntry(long windowStart) {
            this.windowStart = windowStart;
        }

        boolean isWindowExpired(long currentTime, long windowMs) {
            return currentTime - windowStart > windowMs;
        }
    }
    
    public RateLimitingMiddleware() {
        // Cleanup executor will be initialized lazily when first request is processed
    }
    
    public RateLimitingMiddleware(int maxRequests, long timeWindowMs) {
        this();
        this.maxRequests = maxRequests;
        this.timeWindowMs = timeWindowMs;
    }
    
    public RateLimitingMiddleware(int maxRequests, long timeWindowMs, boolean trustToIPHeaders) {
        this();
        this.maxRequests = maxRequests;
        this.timeWindowMs = timeWindowMs;
        this.trustToIPHeaders = trustToIPHeaders;
    }
    
    @Override
    public Response handle(RequestContext context, Request request, HandlerExecutionChain chain) throws Exception {
        if (!enabled) {
            return chain.proceed(context, request);
        }
        
        // Initialize cleanup executor lazily on first request
        initializeCleanupExecutor();
        
        String clientId = getClientIdentifier(request);
        final long currentTime = clock.getAsLong();
        final long windowMs = timeWindowMs;

        //window check, reset and count happen atomically per client, and atomically with cleanup
        final int[] count = new int[1];
        RateLimitEntry entry = rateLimitMap.compute(clientId, (key, existing) -> {
            RateLimitEntry e = (existing == null || existing.isWindowExpired(currentTime, windowMs)) ?
                    new RateLimitEntry(currentTime) : existing;
            count[0] = ++e.requestCount;
            return e;
        });
        int currentCount = count[0];

        // Check if client has exceeded rate limit
        if (currentCount > maxRequests) {
            return createRateLimitExceededResponse(currentCount, maxRequests, windowMs);
        }

        Response response = chain.proceed(context, request);
        if (response == null) return null;
        // Add rate limit headers to response
        response.headers.add("rate_limit_remaining", Integer.toString(maxRequests - currentCount));
        response.headers.add("rate_limit_reset", Long.toString(entry.windowStart + windowMs));
        
        return response;
    }
    
    private void initializeCleanupExecutor() {
        synchronized (executorLock) {
            if (cleanupExecutor == null || cleanupExecutor.isShutdown()) {
                cleanupExecutor = Executors.newSingleThreadScheduledExecutor();
                // Start cleanup task - using scheduleWithFixedDelay to avoid Android cached process issues
                cleanupExecutor.scheduleWithFixedDelay(new Runnable() {
                    @Override
                    public void run() {
                        cleanupExpiredEntries();
                    }
                }, CLEANUP_INTERVAL_MS, CLEANUP_INTERVAL_MS, TimeUnit.MILLISECONDS);
            }
        }
    }
    
    /**
     * The address a request is counted against. Forwarding headers are client-controlled - any
     * client can send "X-Forwarded-For: 1.2.3.4" and get a fresh bucket per request - so they
     * are only believed when the connection itself comes from a trusted proxy: one added with
     * {@link #addTrustedProxy}, or, while none is, a proxy on this very machine (loopback).
     */
    String getClientIdentifier(Request request) {
        String directConnectionIp = request.hostAddress;
        String clientIp = null;

        if (trustToIPHeaders && isTrustedProxy(directConnectionIp)) {
            clientIp = getClientIpFromForwardedHeaders(request);
        }

        if (clientIp == null) {
            clientIp = directConnectionIp;
        }
        return clientIp != null ? clientIp : "unknown";
    }

    private boolean isTrustedProxy(String ip) {
        if (ip == null) {
            return false;
        }
        if (trustedProxies.isEmpty()) {
            return isLoopback(ip);
        }
        return trustedProxies.contains(ip);
    }

    private static boolean isLoopback(String ip) {
        if (!isValidIpAddress(ip)) {
            return false;
        }
        try {
            //a literal address is parsed, never resolved
            return InetAddress.getByName(ip).isLoopbackAddress();
        } catch (UnknownHostException | SecurityException e) {
            return false;
        }
    }

    private String getClientIpFromForwardedHeaders(Request request) {
        //each proxy appends the address it got the request from, so the list reads
        //"client, proxy1, proxy2": walking it from the right, the first hop that is not one of
        //our trusted proxies is the client as far as anything we trust can tell. Entries left of
        //it were written by the client itself and prove nothing
        String xForwardedFor = joinAll(request, HEADER_X_FORWARDED_FOR);
        if (xForwardedFor != null) {
            String[] hops = xForwardedFor.split(",");
            for (int i = hops.length - 1; i >= 0; i--) {
                String hop = hops[i].trim();
                if (!isValidIpAddress(hop)) {
                    //garbage in the chain: nothing to the left of it can be attributed
                    break;
                }
                if (i == 0 || !isTrustedProxy(hop)) {
                    return hop;
                }
            }
        }

        //single-value headers a trusted proxy overwrites rather than appends to
        for (String header : new String[]{HEADER_X_REAL_IP, HEADER_X_CLIENT_IP}) {
            String value = request.headers.get(header);
            if (value != null && isValidIpAddress(value.trim())) {
                return value.trim();
            }
        }

        return null;
    }

    private static String joinAll(Request request, String header) {
        java.util.List<String> values = request.headers.getAll(header);
        return values.isEmpty() ? null : String.join(",", values);
    }

    private static boolean isValidIpAddress(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }

        // Basic IPv4 validation (simple regex for performance)
        if (ip.matches("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")) {
            return true;
        }

        // Basic IPv6 validation: hex digits and colons only
        return ip.indexOf(':') >= 0 && ip.matches("^[0-9a-fA-F:]+$");
    }

    private Response createRateLimitExceededResponse(int currentCount, int maxRequests, long timeWindowMs) {
        long resetTime = System.currentTimeMillis() + timeWindowMs;
        long retryAfter = timeWindowMs / 1000; // Convert to seconds
        
        TextResponse response = new TextResponse(429, "Too Many Requests", 
            "Rate limit exceeded. You have made " + currentCount + " requests. " +
            "Limit is " + maxRequests + " requests per " + (timeWindowMs / 1000) + " seconds. " +
            "Please try again in " + retryAfter + " seconds.");
        
        // Add standard rate limit headers
        response.headers.add("X-RateLimit-Limit", String.valueOf(maxRequests));
        response.headers.add("X-RateLimit-Remaining", "0");
        response.headers.add("X-RateLimit-Reset", String.valueOf(resetTime / 1000));
        response.headers.add("Retry-After", String.valueOf(retryAfter));
        
        return response;
    }
    
    void cleanupExpiredEntries() {
        final long currentTime = clock.getAsLong();
        final long windowMs = timeWindowMs;
        for (String clientId : rateLimitMap.keySet()) {
            //atomic with the compute() in handle: a request counted right now is never lost
            rateLimitMap.computeIfPresent(clientId, (key, entry) ->
                    entry.isWindowExpired(currentTime, windowMs) ? null : entry);
        }
        
        // If no active rate limits remain, stop the cleanup executor to save battery
        if (rateLimitMap.isEmpty() && cleanupExecutor != null) {
            synchronized (executorLock) {
                if (rateLimitMap.isEmpty() && cleanupExecutor != null) {
                    cleanupExecutor.shutdown();
                    cleanupExecutor = null;
                }
            }
        }
    }
    
    // Configuration methods
    public void setMaxRequests(int maxRequests) {
        this.maxRequests = maxRequests;
    }
    
    public void setTimeWindowMs(long timeWindowMs) {
        this.timeWindowMs = timeWindowMs;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public int getMaxRequests() {
        return maxRequests;
    }
    
    public long getTimeWindowMs() {
        return timeWindowMs;
    }
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public int getActiveClients() {
        return rateLimitMap.size();
    }
    
    public void clearRateLimits() {
        rateLimitMap.clear();
    }
    
    // Trusted proxy management methods
    public void addTrustedProxy(String proxyIp) {
        if (proxyIp != null && !proxyIp.trim().isEmpty()) {
            trustedProxies.add(proxyIp.trim());
        }
    }
    
    public void removeTrustedProxy(String proxyIp) {
        trustedProxies.remove(proxyIp);
    }
    
    public void clearTrustedProxies() {
        trustedProxies.clear();
    }
    
    public Set<String> getTrustedProxies() {
        return new HashSet<String>(trustedProxies);
    }
    
    /** For tests: the time source the windows are measured with. */
    void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    public void setTrustToIPHeaders(boolean trustToIPHeaders) {
        this.trustToIPHeaders = trustToIPHeaders;
    }
    
    public boolean isTrustToIPHeaders() {
        return trustToIPHeaders;
    }
    
    public boolean isCleanupExecutorRunning() {
        synchronized (executorLock) {
            return cleanupExecutor != null && !cleanupExecutor.isShutdown();
        }
    }
    
    public void shutdown() {
        synchronized (executorLock) {
            if (cleanupExecutor != null) {
                cleanupExecutor.shutdown();
                try {
                    if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        cleanupExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    cleanupExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                cleanupExecutor = null;
            }
        }
    }
}
