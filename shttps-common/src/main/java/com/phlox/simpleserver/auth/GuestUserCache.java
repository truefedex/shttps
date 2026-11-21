package com.phlox.simpleserver.auth;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.LongSupplier;

/**
 * The guest account as the auth managers see it, re-read from the {@link UserStore} at most once
 * per {@link #DEFAULT_TTL_MS}.
 * <p>
 * Every anonymous request asks for the guest, and with {@code DBBasedUserStore} each lookup is a
 * database query. Holding the answer for the lifetime of the server was the other extreme: a guest
 * the administrator removed kept being served until a restart. A few seconds of staleness - in
 * both directions, including a guest that does not exist - is the compromise.
 */
public final class GuestUserCache {
    public static final long DEFAULT_TTL_MS = 5_000;

    private static final class Entry {
        final @Nullable User user;
        final long readAt;

        Entry(@Nullable User user, long readAt) {
            this.user = user;
            this.readAt = readAt;
        }
    }

    private final @NotNull UserStore userStore;
    private final long ttlMs;
    private final @NotNull LongSupplier clock;
    private volatile @Nullable Entry entry;

    public GuestUserCache(@NotNull UserStore userStore) {
        this(userStore, DEFAULT_TTL_MS, System::nanoTime);
    }

    /** @param clockNanos a monotonic clock in nanoseconds, replaceable in tests */
    public GuestUserCache(@NotNull UserStore userStore, long ttlMs, @NotNull LongSupplier clockNanos) {
        this.userStore = userStore;
        this.ttlMs = ttlMs;
        this.clock = clockNanos;
    }

    public @Nullable User get() {
        long now = clock.getAsLong();
        Entry current = entry;
        if (current == null || now - current.readAt >= ttlMs * 1_000_000L) {
            //two threads may both refresh at the boundary; both read the same store, so either
            //answer is as good as the other
            current = new Entry(userStore.find(User.GUEST_IDENTITY), now);
            entry = current;
        }
        return current.user;
    }
}
