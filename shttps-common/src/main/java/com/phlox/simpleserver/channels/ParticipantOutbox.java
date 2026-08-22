package com.phlox.simpleserver.channels;

import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.server.websocket.WebSocketCloseCodes;
import com.phlox.server.websocket.WebSocketSession;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayDeque;

/**
 * One connection's pending outgoing frames, in the order the channel numbered them.
 * <p>
 * This is what makes {@code seq} mean something on the wire. Frames are <b>queued</b> while the
 * channel's lock is held, so the queue of every recipient is filled in {@code seq} order; they are
 * <b>written</b> once that lock is gone, so a participant that has stopped reading still cannot
 * stall anybody else's command. Before this existed both halves happened after the lock, on the
 * sending connection's own thread, and two participants sending at the same moment raced to write
 * into each recipient's socket - every number arrived, in whatever order the two threads won a
 * monitor. For an ECHO channel that made a client's gap detection cry wolf; for a STATE channel it
 * meant patches applied in the wrong order and a document that never agreed with the server's
 * again.
 * <p>
 * Only one thread writes at a time: the first one in takes {@link #flushing} and drains, and the
 * others simply return, their frames already queued for whoever is draining. The poll and the
 * clearing of that flag happen in one synchronized block, so a frame queued in the moment a flusher
 * finishes is never left sitting with nobody to send it.
 */
public final class ParticipantOutbox {
    /**
     * A connection that cannot keep up with its own channel. Mirrors HTTP 408 - the same number
     * {@code DBTransactionWebSocketHandler} uses for a client that fell behind, though there it is
     * a client that sent nothing and here one that read nothing.
     */
    public static final int CLOSE_CODE_TOO_SLOW = 4408;

    /**
     * The queue is bounded twice over, and both bounds are derived rather than configured: a
     * setting nobody can reason about is worse than a number that scales with the message size the
     * administrator already chose.
     */
    private static final int MAX_QUEUED_FRAMES = 64;

    private static final SHTTPSLoggerProxy.Logger logger =
            SHTTPSLoggerProxy.getLogger(ParticipantOutbox.class);

    private static final class Frame {
        /** Exactly one of the two is set. */
        final @Nullable String text;
        final @Nullable byte[] bytes;
        final int size;

        Frame(@Nullable String text, @Nullable byte[] bytes) {
            this.text = text;
            this.bytes = bytes;
            this.size = text != null ? text.length() : (bytes == null ? 0 : bytes.length);
        }
    }

    private final @Nullable WebSocketSession session;
    private final int maxQueuedBytes;

    private final ArrayDeque<Frame> queue = new ArrayDeque<>();
    private int queuedBytes = 0;
    private boolean flushing = false;
    /** Latched once, so the decision to give up on this connection is made exactly one time. */
    private boolean overflowed = false;
    private boolean closeSent = false;

    ParticipantOutbox(@Nullable WebSocketSession session, int maxQueuedBytes) {
        this.session = session;
        this.maxQueuedBytes = Math.max(1, maxQueuedBytes);
    }

    /**
     * Queues a text frame. Called with the channel's lock held, which is the whole point - it must
     * therefore never do any I/O.
     *
     * @return whether it was queued; {@code false} means this connection has overflowed and every
     * later frame for it is dropped too, until {@link #flush()} closes it
     */
    synchronized boolean offerText(@NotNull String text) {
        return offer(new Frame(text, null));
    }

    synchronized boolean offerBinary(@NotNull byte[] bytes) {
        return offer(new Frame(null, bytes));
    }

    private boolean offer(@NotNull Frame frame) {
        if (overflowed) return false;
        //the emptiness test matters: one frame larger than the whole allowance still has to be
        //queued, or a message at the maximum size could never be delivered to anybody
        if (!queue.isEmpty() &&
                (queue.size() >= MAX_QUEUED_FRAMES || queuedBytes + frame.size > maxQueuedBytes)) {
            overflowed = true;
            return false;
        }
        queue.add(frame);
        queuedBytes += frame.size;
        return true;
    }

    /**
     * Writes whatever is queued, if nobody else is already doing it. Call without the channel's
     * lock.
     * <p>
     * An overflowed connection is <b>closed and its queue thrown away</b> rather than drained
     * first. Delivering a prefix and then cutting it off is the one genuinely harmful option: a
     * STATE client would be left believing it holds every patch up to some {@code seq} when it does
     * not, which is exactly the silent divergence this class exists to prevent. Closing goes
     * through the ordinary path, so {@code onClose} still runs and the participant is still taken
     * out of the channel and announced as having left.
     */
    void flush() {
        boolean tooSlow;
        synchronized (this) {
            if (flushing) return;
            //checked before the drain as well as during it: overflow usually latches while another
            //thread is stuck writing to this same connection, so by the time anybody gets here
            //there is nothing left to do but throw the queue away
            if (overflowed) {
                queue.clear();
                queuedBytes = 0;
                if (closeSent) return;
                closeSent = true;
                tooSlow = true;
            } else {
                tooSlow = false;
                flushing = true;
            }
        }
        if (tooSlow) {
            closeAsTooSlow();
            return;
        }
        if (session == null) {
            //nothing to write to; only the queueing side of this class is exercised
            synchronized (this) {
                flushing = false;
            }
            return;
        }
        while (true) {
            Frame next;
            synchronized (this) {
                if (overflowed) {
                    //latched by another thread while this one was writing
                    queue.clear();
                    queuedBytes = 0;
                    flushing = false;
                    if (closeSent) return;
                    closeSent = true;
                    break;
                }
                next = queue.poll();
                if (next == null) {
                    flushing = false;
                    return;
                }
                queuedBytes -= next.size;
            }
            try {
                if (next.text != null) {
                    session.sendText(next.text);
                } else if (next.bytes != null) {
                    session.sendBinary(next.bytes);
                }
            } catch (IOException e) {
                logger.d("Channel send to " + session.getRemoteAddress() + " failed: " + e.getMessage());
                synchronized (this) {
                    queue.clear();
                    queuedBytes = 0;
                    flushing = false;
                }
                session.close(WebSocketCloseCodes.ABNORMAL_CLOSURE, "");
                return;
            }
        }
        closeAsTooSlow();
    }

    private void closeAsTooSlow() {
        if (session == null) return;
        logger.w("Dropping a channel participant that could not keep up: " +
                session.getRemoteAddress());
        session.close(CLOSE_CODE_TOO_SLOW, "Too slow to receive this channel's traffic");
    }

    /** How many frames are waiting. For tests: nothing in the protocol depends on it. */
    synchronized int pendingFrames() {
        return queue.size();
    }

    /** Queues one frame for each of several participants and hands back the ones to flush. */
    static void offerTextTo(@NotNull Iterable<Participant> participants, @NotNull String text) {
        for (Participant participant : participants) {
            participant.outbox.offerText(text);
        }
    }

    /** Writes out everything queued for each of them, outside the channel's lock. */
    public static void flushAll(@NotNull Iterable<Participant> participants) {
        for (Participant participant : participants) {
            participant.outbox.flush();
        }
    }
}
