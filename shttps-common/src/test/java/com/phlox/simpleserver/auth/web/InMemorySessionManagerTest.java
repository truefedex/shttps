package com.phlox.simpleserver.auth.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public class InMemorySessionManagerTest {
    private static final long TIMEOUT = 60_000;
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final InMemorySessionManager sessions = new InMemorySessionManager(TIMEOUT, now::get);

    @Test
    public void aSessionNamesItsUserUntilItTimesOut() {
        String id = sessions.createSession("alice");
        assertEquals("alice", sessions.getUsernameBySessionId(id));

        now.addAndGet(TIMEOUT + 1);
        assertNull(sessions.getUsernameBySessionId(id));
        //and it is gone, not merely refused once
        now.addAndGet(-TIMEOUT);
        assertNull(sessions.getUsernameBySessionId(id));
    }

    @Test
    public void useKeepsASessionAlive() {
        String id = sessions.createSession("alice");
        for (int i = 0; i < 5; i++) {
            now.addAndGet(TIMEOUT - 1);
            assertEquals("alice", sessions.getUsernameBySessionId(id));
        }
    }

    @Test
    public void anInvalidatedSessionIsGone() {
        String id = sessions.createSession("alice");
        sessions.invalidateSession(id);
        assertNull(sessions.getUsernameBySessionId(id));
    }

    @Test
    public void cleanupDropsOnlyExpiredSessions() {
        String old = sessions.createSession("old");
        now.addAndGet(TIMEOUT / 2);
        String fresh = sessions.createSession("fresh");
        now.addAndGet(TIMEOUT / 2 + 1);

        sessions.cleanupExpiredSessions();
        now.addAndGet(-(TIMEOUT / 2 + 1));//even back in time, the dropped one does not return
        assertNull(sessions.getUsernameBySessionId(old));
        assertEquals("fresh", sessions.getUsernameBySessionId(fresh));
    }

    @Test
    public void idsAreUnguessablyLongAndDistinct() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String id = sessions.createSession("u");
            //24 random bytes: 32 characters of base64
            assertTrue(id.length() >= 32, id);
            ids.add(id);
        }
        assertEquals(1000, ids.size());
        assertNull(sessions.getUsernameBySessionId("guess"));
        assertNotEquals(sessions.createSession("a"), sessions.createSession("a"));
    }
}
