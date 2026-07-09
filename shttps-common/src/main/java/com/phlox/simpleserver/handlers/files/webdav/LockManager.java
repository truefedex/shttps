package com.phlox.simpleserver.handlers.files.webdav;

import com.phlox.server.utils.RadixTree;

public final class LockManager {

    static final int INFINITY = Integer.MAX_VALUE;

    static final class Lock {
        final String token;      // "urn:uuid:..."
        final String path;       // logical path
        final boolean exclusive; // always true for now
        final int depth;         // 0 or INFINITY
        final String ownerXml;   // can be null
        volatile long expiresAt;

        Lock(String token, String path, boolean exclusive, int depth,
             String ownerXml, long expiresAt) {
            this.token = token; this.path = path; this.exclusive = exclusive;
            this.depth = depth; this.ownerXml = ownerXml; this.expiresAt = expiresAt;
        }
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    private final RadixTree<Lock> tree = new RadixTree<>();
    private final Object mutex = new Object();

    /**
     * Lock that actually acts on this path: either an exact lock on the path,
     * or an infinity lock on some ancestor. If depth=0, the ancestor lock does NOT cover the descendant.
     * Returns null if the path is free.
     */
    Lock effectiveLock(String path) {
        synchronized (mutex) {
            return effectiveLockUnlocked(path);
        }
    }

    private Lock effectiveLockUnlocked(String path) {
        Lock[] hit = { null };
        tree.visitPrefixes(path, (prefixKey, lock) -> {
            if (lock.isExpired()) return true;
            boolean isSelf = prefixKey.equals(path);
            if (isSelf || lock.depth == INFINITY) {
                hit[0] = lock;
                return false;
            }
            return true;
        });
        return hit[0];
    }

    /** Is there a live lock in the subtree under prefix? To issue an infinity lock on a folder. */
    private boolean hasAnyLockInSubtree(String prefix) {
        boolean[] found = { false };
        // keys equal to or longer than prefix (i.e. descendants + the node itself)
        tree.visitPrefixesEqualOrLongerThan(prefix, (key, lock) -> {
            if (lock.isExpired()) return true;
            if (key.equals(prefix)) return true;
            found[0] = true;
            return false;
        });
        return found[0];
    }

    /** Create a lock. Returns null if the path is busy (→ 423 for the caller). */
    Lock acquire(String path, boolean exclusive, int depth,
                 String ownerXml, long timeoutMs) {
        synchronized (mutex) {
            // 1. does the path itself or the infinity ancestor already hold the lock?
            if (effectiveLockUnlocked(path) != null) return null;
            // 2. if we are locking a subtree (infinity) - there should be no foreign locks inside
            if (depth == INFINITY && hasAnyLockInSubtree(path)) return null;

            Lock l = new Lock(
                    "urn:uuid:" + java.util.UUID.randomUUID(),
                    path, true, depth, ownerXml,
                    System.currentTimeMillis() + timeoutMs);
            tree.put(path, l);
            return l;
        }
    }

    /** Refresh the TTL by token (LOCK without body with If). null, if the token is not found/expired (→ 412). */
    Lock refresh(String token, long timeoutMs) {
        synchronized (mutex) {
            Lock[] hit = { null };
            tree.removeIf((key, lock) -> lock.isExpired());
            // We search by token—we search through all of them; there are few locks, so it's cheap.
            tree.visitPrefixesEqualOrLongerThan("", (key, lock) -> {
                if (!lock.isExpired() && lock.token.equals(token)) { hit[0] = lock; return false; }
                return true;
            });
            Lock l = hit[0];
            if (l == null) return null;
            l.expiresAt = System.currentTimeMillis() + timeoutMs;
            return l;
        }
    }

    /** Release a lock by path and token. Returns false if the token does not match (→ 409). */
    boolean release(String path, String token) {
        synchronized (mutex) {
            Lock l = tree.get(path);                            // exact path match
            if (l != null && !l.isExpired() && l.token.equals(token)) {
                tree.remove(path);
                return true;
            }
            return false;
        }
    }

    /** Token validation when writing. Returns true if the operation at the path is allowed. */
    public boolean mayWrite(String path, String providedToken) {
        synchronized (mutex) {
            Lock l = effectiveLockUnlocked(path);
            if (l == null) return true;                         // not locked
            return providedToken != null && providedToken.equals(l.token);
        }
    }

    /** Lazy cleanup of expired locks (can be called by a timer or at the beginning of operations). In-memory, without IO. */
    void sweep() {
        synchronized (mutex) {
            tree.removeIf((key, lock) -> lock.isExpired());
        }
    }
}
