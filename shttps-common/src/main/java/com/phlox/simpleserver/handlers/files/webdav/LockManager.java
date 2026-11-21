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
            //an infinity lock on /a covers /a/b. It does not cover /ab: the next character has to
            //be a segment boundary, same as a path prefix in the access rules.
            if (prefixKey.equals(path) || (lock.depth == INFINITY && covers(prefixKey, path))) {
                hit[0] = lock;
                return false;
            }
            return true;
        });
        return hit[0];
    }

    /** {@code ancestor} is the path itself, or a strict directory prefix of it. */
    static boolean covers(String ancestor, String path) {
        if (ancestor.equals(path)) return true;
        if ("/".equals(ancestor)) return path.startsWith("/");
        String prefix = ancestor.endsWith("/") ? ancestor : ancestor + "/";
        return path.startsWith(prefix);
    }

    /** Is there a live lock in the subtree under prefix? To issue an infinity lock on a folder. */
    private boolean hasAnyLockInSubtree(String prefix) {
        return hasBlockingDescendant(prefix, null, true);
    }

    /**
     * A lock on a child blocks deleting or moving the parent, whichever token the caller showed,
     * unless that token is the child's own. {@code anyTokenBlocks} is the infinity-lock check,
     * which refuses the subtree even when the caller holds one of the tokens.
     */
    private boolean hasBlockingDescendant(String prefix, String providedToken, boolean anyTokenBlocks) {
        boolean[] found = { false };
        tree.visitPrefixesEqualOrLongerThan(prefix, (key, lock) -> {
            if (lock.isExpired()) return true;
            if (!covers(prefix, key) || key.equals(prefix)) return true;
            if (anyTokenBlocks || providedToken == null || !providedToken.equals(lock.token)) {
                found[0] = true;
                return false;
            }
            return true;
        });
        return found[0];
    }

    /** Create a lock. Returns null if the path is busy (→ 423 for the caller). */
    Lock acquire(String path, boolean exclusive, int depth,
                 String ownerXml, long timeoutMs) {
        synchronized (mutex) {
            sweepUnlocked();
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

    /**
     * Refresh the TTL of the lock that covers {@code path}: the lock on the path itself, or an
     * infinity lock on an ancestor (RFC 4918 lets the refresh name any resource in the lock's
     * scope). A token of a lock elsewhere does not extend it. null if no live lock covering this
     * path has that token (→ 412).
     */
    Lock refresh(String path, String token, long timeoutMs) {
        synchronized (mutex) {
            sweepUnlocked();
            Lock l = effectiveLockUnlocked(path);
            if (l == null || !l.token.equals(token)) return null;
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
            return mayWriteUnlocked(path, providedToken);
        }
    }

    /**
     * Writing, plus the locks on descendants. DELETE and MOVE remove the whole subtree, so a lock
     * on a child has to agree too. A plain PUT does not, and keeps using {@link #mayWrite}.
     */
    public boolean mayRemove(String path, String providedToken) {
        synchronized (mutex) {
            return mayWriteUnlocked(path, providedToken)
                    && !hasBlockingDescendant(path, providedToken, false);
        }
    }

    private boolean mayWriteUnlocked(String path, String providedToken) {
        Lock l = effectiveLockUnlocked(path);
        if (l == null) return true;
        return providedToken != null && providedToken.equals(l.token);
    }

    /** Lazy cleanup of expired locks. In-memory, without IO. */
    void sweep() {
        synchronized (mutex) {
            sweepUnlocked();
        }
    }

    private void sweepUnlocked() {
        tree.removeIf((key, lock) -> lock.isExpired());
    }
}
