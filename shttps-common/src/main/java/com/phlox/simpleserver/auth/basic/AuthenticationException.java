package com.phlox.simpleserver.auth.basic;

import com.phlox.server.responses.Response;
import com.phlox.server.responses.TextResponse;

/**
 * Thrown while the brute force backoff is in force. It is not a server failure: left to the
 * server it became a 500, so callers turn it into {@link #toResponse()} - a 429 that says when
 * to try again.
 */
public class AuthenticationException extends RuntimeException {
    private final long retryAfterMillis;

    public AuthenticationException(String message) {
        this(message, 0);
    }

    public AuthenticationException(String message, long retryAfterMillis) {
        super(message);
        this.retryAfterMillis = Math.max(0, retryAfterMillis);
    }

    public long getRetryAfterMillis() {
        return retryAfterMillis;
    }

    public Response toResponse() {
        TextResponse response = new TextResponse(429, "Too Many Requests", getMessage());
        //whole seconds, rounded up: "0" would invite an immediate retry that is refused again
        long seconds = Math.max(1, (retryAfterMillis + 999) / 1000);
        response.headers.add("Retry-After", Long.toString(seconds));
        return response;
    }
}
