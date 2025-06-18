package com.phlox.simpleserver.auth.web;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.auth.UserStore;
import com.phlox.simpleserver.auth.basic.AuthenticationException;
import com.phlox.simpleserver.utils.Utils;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public class WebAuthManager implements AuthManager {
    public static final String CONTEXT_KEY_WEB_AUTH_USER = "web_auth_user";
    public static final String COOKIE_KEY_SESSION_ID = "SESSION_ID";

    private static final long INITIAL_BACKOFF_MS = 1000; // 1 second
    private static final long MAX_BACKOFF_MS = 30000; // 30 seconds
    private static final long BACKOFF_RESET_MS = 300000; // 5 minutes
    private UserStore userStore;
    private SessionManager sessionManager;

    private final User guestUser;
    private final AtomicLong failedAttempts = new AtomicLong(0);
    private volatile long lastAttemptTime = 0;

    public WebAuthManager(UserStore userStore, SessionManager sessionManager) {
        this.userStore = userStore;
        this.sessionManager = sessionManager;
        this.guestUser = userStore.find(User.GUEST_IDENTITY);
    }

    @Override
    public @Nullable User getAuthenticatedUser(@NonNull RequestContext context) {
        Object user = context.data.get(CONTEXT_KEY_WEB_AUTH_USER);
        return user instanceof User ? (User) user : null;
    }

    @Override
    public User authenticate(RequestContext context, Request request) {
        String sessionId = request.cookies.get(COOKIE_KEY_SESSION_ID);
        User user = null;
        if (sessionId != null) {
            String username = sessionManager.getUsernameBySessionId(sessionId);
            user = userStore.find(username);
        }
        if (user == null && guestUser != null) {
            user = guestUser;
        }
        if (user != null) {
            context.data.put(CONTEXT_KEY_WEB_AUTH_USER, user);
            return user;
        }

        return null;
    }

    public User login(RequestContext context, Request request) {
        String username = request.urlEncodedPostParams.get("username");
        String passwordHash = request.urlEncodedPostParams.get("password");
        if (username == null || passwordHash == null) {
            return null;
        }

        // Check if we need to apply backoff
        long currentTime = System.currentTimeMillis();
        long timeSinceLastAttempt = currentTime - lastAttemptTime;

        if (timeSinceLastAttempt > BACKOFF_RESET_MS) {
            failedAttempts.set(0);
        } else if (failedAttempts.get() > 0) {
            //prevent brute force attacks: double the backoff time each time
            long backoffTime = Math.min(INITIAL_BACKOFF_MS * (1L << (failedAttempts.get() - 1)), MAX_BACKOFF_MS);
            if (timeSinceLastAttempt < backoffTime) {
                throw new AuthenticationException("Too many failed attempts. Please try again later.");
            }
        }

        try {
            assert passwordHash != null;
            User user = userStore.authenticate(username, Objects.requireNonNull(Utils.sha256(passwordHash)));
            if (user != null) {
                failedAttempts.set(0);
                context.data.put(CONTEXT_KEY_WEB_AUTH_USER, user);
                return user;
            }

            failedAttempts.incrementAndGet();
            lastAttemptTime = currentTime;
            return null;
        } catch (Exception e) {
            failedAttempts.incrementAndGet();
            lastAttemptTime = currentTime;
            return null;
        }
    }

    @Override
    public void logout(@NonNull RequestContext context, @NonNull Request request) {
        String sessionId = request.cookies.get(COOKIE_KEY_SESSION_ID);
        if (sessionId != null) {
            sessionManager.invalidateSession(sessionId);
        }
        sessionManager.cleanupExpiredSessions();
    }
}
