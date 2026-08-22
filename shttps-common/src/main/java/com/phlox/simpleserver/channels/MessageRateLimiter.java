package com.phlox.simpleserver.channels;

/**
 * How many messages one connection may send per second, §2 - a token bucket, one per
 * {@code WebSocketSession}.
 * <p>
 * This is not the same thing as the {@code RateLimitingMiddleware} the HTTP routes go through:
 * that one sees a handshake, or a one-shot publish, as a single request and then never hears from
 * the connection again. Nothing before this counted how much a participant sends once its socket is
 * open.
 * <p>
 * A bucket rather than a counter per whole second, because a limit of 20 should let a client that
 * sends steadily keep sending, instead of letting one that happens to straddle a second boundary
 * send 40 in a row and refusing another that spread the same 20 evenly.
 */
public class MessageRateLimiter {
    private final int permitsPerSecond;
    private double tokens;
    private long lastRefillAt;

    /**
     * @param permitsPerSecond both the refill rate and the size of the bucket; zero or less turns
     *                         the limit off entirely, the way {@code idlePingIntervalMillis = 0}
     *                         turns off pings, rather than refusing everything
     */
    public MessageRateLimiter(int permitsPerSecond) {
        this(permitsPerSecond, System.currentTimeMillis());
    }

    MessageRateLimiter(int permitsPerSecond, long now) {
        this.permitsPerSecond = permitsPerSecond;
        //a connection starts with a full bucket: the first messages of a client that has only just
        //arrived are the least likely to be a flood
        this.tokens = permitsPerSecond;
        this.lastRefillAt = now;
    }

    public synchronized boolean tryAcquire() {
        return tryAcquire(System.currentTimeMillis());
    }

    /**
     * The clock is a parameter so the test can drive it instead of sleeping through real seconds.
     *
     * @return whether the message may be handled
     */
    synchronized boolean tryAcquire(long now) {
        if (permitsPerSecond <= 0) {
            return true;
        }
        //a clock that went backwards - a manual change, an NTP step - must not drain the bucket, so
        //time that ran the wrong way simply counts as none having passed
        long elapsed = Math.max(0, now - lastRefillAt);
        tokens = Math.min(permitsPerSecond, tokens + elapsed / 1000.0 * permitsPerSecond);
        lastRefillAt = now;
        if (tokens < 1.0) {
            return false;
        }
        tokens -= 1.0;
        return true;
    }
}
