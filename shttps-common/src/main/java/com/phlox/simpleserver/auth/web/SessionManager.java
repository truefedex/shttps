package com.phlox.simpleserver.auth.web;

public interface SessionManager {
    String createSession(String username);
    String getUsernameBySessionId(String sessionId);
    void invalidateSession(String sessionId);
    void cleanupExpiredSessions();
}
