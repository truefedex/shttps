package com.phlox.simpleserver.screenshare;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Someone asking to watch the screen, waiting for the person at the device to let them in. Handed
 * to a {@link ScreenAccessApprover}, which shows it and passes the answer back through
 * {@link #answer}.
 * <p>
 * A request is settled exactly once - by an answer, by running out of time, or by the client going
 * away first - and whichever comes first wins. An answer to a request that is already settled is
 * ignored, which is what makes it safe for a prompt to still be on screen a moment after its
 * client disappeared.
 */
public final class ScreenAccessRequest {

    public enum Decision {
        /** Let the viewer in with everything their account and the app settings allow. */
        ALLOW,
        /** Let the viewer watch, but not control, even if they otherwise could. */
        VIEW_ONLY,
        DENY
    }

    /** What the endpoint does with an answer. */
    interface Listener {
        void onAnswered(ScreenAccessRequest request, Decision decision);
    }

    private static final AtomicLong NEXT_ID = new AtomicLong(1);

    /** Unique for the life of the process, so a platform can key its prompt (a notification id) on it. */
    public final long id;
    /** IP address of the client asking. */
    public final @NotNull String remoteAddress;
    /** The account the client logged in with, or null when authentication is off. */
    public final @Nullable String userName;
    /**
     * Whether letting this client in would also let it control the machine - its account may and
     * remote control is available right now. Only then does {@link Decision#VIEW_ONLY} mean
     * anything different from {@link Decision#ALLOW}.
     */
    public final boolean includesControl;

    private final AtomicBoolean settled = new AtomicBoolean(false);
    private final Listener listener;

    ScreenAccessRequest(@NotNull String remoteAddress, @Nullable String userName,
                        boolean includesControl, @NotNull Listener listener) {
        this.id = NEXT_ID.getAndIncrement();
        this.remoteAddress = remoteAddress;
        this.userName = userName;
        this.includesControl = includesControl;
        this.listener = listener;
    }

    /**
     * Passes the local user's answer on. May be called from any thread, and returns at once - the
     * connection is set up or closed elsewhere.
     *
     * @return false if the request was already settled, by an earlier answer, a timeout or the
     * client leaving, in which case nothing happens
     */
    public boolean answer(@NotNull Decision decision) {
        Objects.requireNonNull(decision);
        if (!settle()) {
            return false;
        }
        listener.onAnswered(this, decision);
        return true;
    }

    public boolean isSettled() {
        return settled.get();
    }

    /** Settles the request without an answer. @return whether this call was the one that did. */
    boolean settle() {
        return settled.compareAndSet(false, true);
    }

    @Override
    public String toString() {
        return "ScreenAccessRequest{" + id + ", " + remoteAddress +
                (userName == null ? "" : ", user " + userName) +
                (includesControl ? ", with control}" : "}");
    }
}
