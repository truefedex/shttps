package com.phlox.simpleserver.auth.basic;

import com.phlox.server.platform.Base64;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.auth.UserRightsEvaluator;
import com.phlox.simpleserver.auth.UserStore;
import com.phlox.simpleserver.utils.Utils;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

public class BasicAuthManager implements AuthManager {
    public static final String CONTEXT_KEY_BASIC_AUTH_USER = "basic_auth_user";
    private static final long INITIAL_BACKOFF_MS = 1000; // 1 second
    private static final long MAX_BACKOFF_MS = 30000; // 30 seconds
    private static final long BACKOFF_RESET_MS = 300000; // 5 minutes
    
    private volatile long lastAttemptTime = 0;
    private final UserStore userStore;
    private final User guestUser;
    private final AtomicLong failedAttempts = new AtomicLong(0);

    public BasicAuthManager(UserStore userStore) {
        this.userStore = userStore;
        this.guestUser = userStore.find(User.GUEST_IDENTITY);
    }

    @Override
    public @Nullable User getAuthenticatedUser(@NonNull RequestContext context) {
        Object user = context.data.get(CONTEXT_KEY_BASIC_AUTH_USER);
        return user instanceof User ? (User) user : null;
    }

    @Override
    public void logout(@NonNull RequestContext context, @NonNull Request request) {
        //Unimplementable for basic auth
    }

    @Override
    public User authenticate(RequestContext context, Request request) {
        String authorization = request.headers.get(Request.HEADER_AUTHORIZATION);
        if (authorization == null || !authorization.toLowerCase().startsWith("basic")) {
            if (guestUser != null) {
                context.data.put(CONTEXT_KEY_BASIC_AUTH_USER, guestUser);
                return guestUser;
            }
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
            String base64Credentials = authorization.substring("Basic".length()).trim();
            byte[] credDecoded = Base64.decode(base64Credentials);
            String credentials = new String(credDecoded, StandardCharsets.UTF_8);
            
            // credentials = username:password
            final String[] values = credentials.split(":", 2);
            if (values.length != 2) {
                return null;
            }

            String username = values[0];
            String password = values[1];
            String passwordHash = Utils.sha256(Utils.hashFNV1a32(password));

            assert passwordHash != null;
            User user = userStore.authenticate(username, passwordHash);
            if (user != null) {
                failedAttempts.set(0);
                context.data.put(CONTEXT_KEY_BASIC_AUTH_USER, user);
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
    public @NonNull UserRightsEvaluator getUserRightsEvaluator() {
        return userStore.provideUserRightsEvaluator();
    }
}
