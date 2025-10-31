package com.phlox.server.handlers;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.TextResponse;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Set;
import java.util.HashSet;

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
    private final Set<String> trustedProxies = new HashSet<String>();
    private volatile boolean trustToIPHeaders = true;
    
    // Rate limit entry for tracking requests
    private static class RateLimitEntry {
        private final AtomicInteger requestCount = new AtomicInteger(0);
        private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
        
        
        public long getWindowStart() {
            return windowStart.get();
        }
        
        public int incrementAndGet() {
            return requestCount.incrementAndGet();
        }
        
        public void resetWindow() {
            requestCount.set(0);
            windowStart.set(System.currentTimeMillis());
        }
        
        public boolean isWindowExpired(long currentTime, long windowMs) {
            return currentTime - windowStart.get() > windowMs;
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
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        if (!enabled) {
            return null; // Allow request to proceed
        }
        
        // Initialize cleanup executor lazily on first request
        initializeCleanupExecutor();
        
        String clientId = getClientIdentifier(request);
        long currentTime = System.currentTimeMillis();
        
        // Get or create rate limit entry for this client
        RateLimitEntry entry = rateLimitMap.get(clientId);
        if (entry == null) {
            entry = new RateLimitEntry();
            RateLimitEntry existing = rateLimitMap.putIfAbsent(clientId, entry);
            if (existing != null) {
                entry = existing;
            }
        }
        
        // Check if current window has expired
        if (entry.isWindowExpired(currentTime, timeWindowMs)) {
            entry.resetWindow();
        }
        
        // Check if client has exceeded rate limit
        int currentCount = entry.incrementAndGet();
        if (currentCount > maxRequests) {
            return createRateLimitExceededResponse(currentCount, maxRequests, timeWindowMs);
        }
        
        // Add rate limit headers to response
        context.additionalResponseHeaders.put("rate_limit_remaining", Integer.toString(maxRequests - currentCount));
        context.additionalResponseHeaders.put("rate_limit_reset", Long.toString(entry.getWindowStart() + timeWindowMs));
        
        return null; // Allow request to proceed
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
    
    private String getClientIdentifier(Request request) {
        String clientIp = null;
        String directConnectionIp = request.hostAddress;
        
        // Trust IP headers if enabled and either no trusted proxies configured or coming from trusted proxy
        if (trustToIPHeaders && (trustedProxies.isEmpty() || isTrustedProxy(directConnectionIp))) {
            // Try to get client IP from forwarded headers
            clientIp = getClientIpFromForwardedHeaders(request);
        }
        
        // If no trusted proxy header found, or not using trusted proxies, use direct connection IP
        if (clientIp == null) {
            clientIp = directConnectionIp;
        }
        
        // Fallback to a default identifier if no IP is available
        return clientIp != null ? clientIp : "unknown";
    }
    
    private boolean isTrustedProxy(String proxyIp) {
        if (proxyIp == null || trustedProxies.isEmpty()) {
            return false;
        }
        return trustedProxies.contains(proxyIp);
    }
    
    private String getClientIpFromForwardedHeaders(Request request) {
        // Try X-Forwarded-For first (most common)
        String xForwardedFor = request.headers.get("X-Forwarded-For");
        if (xForwardedFor != null) {
            String clientIp = extractFirstIpFromList(xForwardedFor);
            if (clientIp != null) {
                return clientIp;
            }
        }
        
        // Try X-Real-IP (single IP)
        String xRealIp = request.headers.get("X-Real-IP");
        if (xRealIp != null) {
            String clientIp = extractFirstIpFromList(xRealIp);
            if (clientIp != null) {
                return clientIp;
            }
        }
        
        // Try X-Client-IP (single IP)
        String xClientIp = request.headers.get("X-Client-IP");
        if (xClientIp != null) {
            String clientIp = extractFirstIpFromList(xClientIp);
            if (clientIp != null) {
                return clientIp;
            }
        }
        
        return null;
    }
    
    private String extractFirstIpFromList(String ipList) {
        if (ipList == null || ipList.trim().isEmpty()) {
            return null;
        }
        
        // Split by comma and get the first (original client) IP
        String[] ips = ipList.split(",");
        if (ips.length > 0) {
            String firstIp = ips[0].trim();
            // Basic IP validation (IPv4 or IPv6)
            if (isValidIpAddress(firstIp)) {
                return firstIp;
            }
        }
        
        return null;
    }
    
    private boolean isValidIpAddress(String ip) {
        if (ip == null || ip.trim().isEmpty()) {
            return false;
        }
        
        String trimmedIp = ip.trim();
        
        // Basic IPv4 validation (simple regex for performance)
        if (trimmedIp.matches("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")) {
            return true;
        }
        
        // Basic IPv6 validation (contains colons)
        if (trimmedIp.contains(":") && !trimmedIp.contains(".")) {
            return true;
        }
        
        return false;
    }
    
    private Response createRateLimitExceededResponse(int currentCount, int maxRequests, long timeWindowMs) {
        long resetTime = System.currentTimeMillis() + timeWindowMs;
        long retryAfter = timeWindowMs / 1000; // Convert to seconds
        
        TextResponse response = new TextResponse(429, "Too Many Requests", 
            "Rate limit exceeded. You have made " + currentCount + " requests. " +
            "Limit is " + maxRequests + " requests per " + (timeWindowMs / 1000) + " seconds. " +
            "Please try again in " + retryAfter + " seconds.");
        
        // Add standard rate limit headers
        response.headers.put("X-RateLimit-Limit", String.valueOf(maxRequests));
        response.headers.put("X-RateLimit-Remaining", "0");
        response.headers.put("X-RateLimit-Reset", String.valueOf(resetTime / 1000));
        response.headers.put("Retry-After", String.valueOf(retryAfter));
        
        return response;
    }
    
    private void cleanupExpiredEntries() {
        long currentTime = System.currentTimeMillis();
        java.util.Iterator<java.util.Map.Entry<String, RateLimitEntry>> iterator = rateLimitMap.entrySet().iterator();
        while (iterator.hasNext()) {
            java.util.Map.Entry<String, RateLimitEntry> entry = iterator.next();
            RateLimitEntry rateLimitEntry = entry.getValue();
            if (rateLimitEntry.isWindowExpired(currentTime, timeWindowMs)) {
                iterator.remove();
            }
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
