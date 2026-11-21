package com.phlox.simpleserver.auth.web;

import com.phlox.server.platform.Base64;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    private String generateSessionId() {
        byte[] randomBytes = new byte[24];
        secureRandom.nextBytes(randomBytes);
        return Base64.encodeToString(randomBytes, true);
    }

    @Override
    public String createSession(String username) {
        String sessionId = generateSessionId();
        long expiresAt = System.currentTimeMillis() + SESSION_TIMEOUT_MILLIS;
        sessions.put(sessionId, new Session(username, expiresAt));
        return sessionId;
    }

    @Override
    public String getUsernameBySessionId(String sessionId) {
        Session session = sessions.get(sessionId);
        if (session == null) return null;

        long now = System.currentTimeMillis();
        if (session.expiresAtMillis < now) {
            sessions.remove(sessionId);
            return null;
        }
        session.expiresAtMillis = now + SESSION_TIMEOUT_MILLIS;

        return session.username;
    }

    @Override
    public void invalidateSession(String sessionId) {
        sessions.remove(sessionId);
    }

    @Override
    public void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
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
