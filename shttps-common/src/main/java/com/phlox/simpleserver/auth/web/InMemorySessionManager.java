package com.phlox.simpleserver.auth.web;

import com.phlox.server.platform.Base64;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

public class InMemorySessionManager implements SessionManager {
    private static final long SESSION_TIMEOUT_MILLIS = 60 * 60 * 1000; // 1 hour
    private static final SecureRandom secureRandom = new SecureRandom();

    private static class Session {
        String username;
        long expiresAtMillis;

        Session(String username, long expiresAtMillis) {
            this.username = username;
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final long timeoutMillis;
    private final LongSupplier clock;

    public InMemorySessionManager() {
        this(SESSION_TIMEOUT_MILLIS, System::currentTimeMillis);
    }

    /** @param clock wall-clock milliseconds, replaceable in tests */
    public InMemorySessionManager(long timeoutMillis, LongSupplier clock) {
        this.timeoutMillis = timeoutMillis;
        this.clock = clock;
    }

    private String generateSessionId() {
        byte[] randomBytes = new byte[24];
        secureRandom.nextBytes(randomBytes);
        return Base64.encodeToString(randomBytes, true);
    }

    @Override
    public String createSession(String username) {
        String sessionId = generateSessionId();
        long expiresAt = clock.getAsLong() + timeoutMillis;
        sessions.put(sessionId, new Session(username, expiresAt));
        return sessionId;
    }

    @Override
    public String getUsernameBySessionId(String sessionId) {
        Session session = sessions.get(sessionId);
        if (session == null) return null;

        long now = clock.getAsLong();
        if (session.expiresAtMillis < now) {
            sessions.remove(sessionId);
            return null;
        }
        session.expiresAtMillis = now + timeoutMillis;

        return session.username;
    }

    @Override
    public void invalidateSession(String sessionId) {
        sessions.remove(sessionId);
    }

    @Override
    public void cleanupExpiredSessions() {
        long now = clock.getAsLong();
        //need Android API >= 24
        //sessions.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis < now);
        for (Iterator<Map.Entry<String, Session>> it = sessions.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Session> entry = it.next();
            if (entry.getValue().expiresAtMillis < now) {
                it.remove();
            }
        }
    }
}
