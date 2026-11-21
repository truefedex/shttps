package com.phlox.simpleserver.handlers.files.webdav;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class LockManagerTest {

    @Test
    public void anInfinityLockStopsAtTheSegmentBoundary() {
        LockManager locks = new LockManager();
        LockManager.Lock lock = locks.acquire("/a", true, LockManager.INFINITY, null, 60_000);

        assertFalse(locks.mayWrite("/a/b", null));
        assertFalse(locks.mayWrite("/a", "other"));
        assertTrue(locks.mayWrite("/ab", null));
        assertTrue(locks.mayWrite("/a/b", lock.token));
    }

    @Test
    public void aDepthZeroLockDoesNotCoverChildren() {
        LockManager locks = new LockManager();
        locks.acquire("/a", true, 0, null, 60_000);

        assertTrue(locks.mayWrite("/a/b", null));
        assertFalse(locks.mayWrite("/a", null));
    }

    @Test
    public void refreshAppliesOnlyToTheLockOnThatPath() {
        LockManager locks = new LockManager();
        LockManager.Lock lock = locks.acquire("/a", true, 0, null, 60_000);

        assertNull(locks.refresh("/b", lock.token, 60_000));
        assertNotNull(locks.refresh("/a", lock.token, 60_000));
        assertNull(locks.refresh("/a", "urn:uuid:nope", 60_000));
    }

    @Test
    public void anInfinityLockCanBeRefreshedThroughAResourceInItsScope() {
        LockManager locks = new LockManager();
        LockManager.Lock lock = locks.acquire("/a", true, LockManager.INFINITY, null, 60_000);

        assertNotNull(locks.refresh("/a/b/c", lock.token, 60_000));
        assertNull(locks.refresh("/ab", lock.token, 60_000));
    }

    @Test
    public void deletingAParentSeesALockOnItsChild() {
        LockManager locks = new LockManager();
        LockManager.Lock child = locks.acquire("/a/b", true, 0, null, 60_000);

        assertTrue(locks.mayWrite("/a", null));
        assertFalse(locks.mayRemove("/a", null));
        assertTrue(locks.mayRemove("/a", child.token));
        assertTrue(locks.mayRemove("/ab", null));
    }
}
