package com.phlox.simpleserver.channels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The bound on a participant's pending frames, on its own.
 * <p>
 * Only the queueing half is exercised here: {@link ParticipantOutbox#flush()} needs a real socket
 * to write to, and a queue only grows in the first place when a write is blocking, which is not
 * something an in-process test can arrange without becoming a race of its own. What can be pinned
 * down exactly is the decision itself - when the outbox gives up on a connection, and that it stays
 * given up on rather than accepting more.
 */
public class ParticipantOutboxTest {
    /** No session: nothing is ever written, so what is queued stays queued to be counted. */
    private static ParticipantOutbox outbox(int maxQueuedBytes) {
        return new ParticipantOutbox(null, maxQueuedBytes);
    }

    private static String frameOf(int bytes) {
        return new String(new char[bytes]).replace('\0', 'x');
    }

    @Test
    public void queuesUpToTheByteBoundAndThenGivesUp() {
        ParticipantOutbox outbox = outbox(1000);
        assertTrue(outbox.offerText(frameOf(400)));
        assertTrue(outbox.offerText(frameOf(400)));
        assertFalse(outbox.offerText(frameOf(400)),
                "a third frame takes the queue past its allowance and must be refused");
    }

    @Test
    public void oneFrameLargerThanTheWholeAllowanceIsStillQueued() {
        //otherwise a message at the maximum size could never reach anybody: the bound is a multiple
        //of that size, but a queue that refuses its own first frame would deliver nothing at all
        ParticipantOutbox outbox = outbox(100);
        assertTrue(outbox.offerText(frameOf(5000)));
        assertFalse(outbox.offerText(frameOf(1)), "the queue is over its allowance now");
    }

    @Test
    public void givingUpIsLatched() {
        ParticipantOutbox outbox = outbox(100);
        assertTrue(outbox.offerText(frameOf(90)));
        assertFalse(outbox.offerText(frameOf(90)));
        //the connection is on its way out; nothing more is kept for it, however small, and however
        //much room clearing the queue would appear to free
        assertFalse(outbox.offerText(frameOf(1)));
        assertFalse(outbox.offerBinary(new byte[]{1}));
    }

    @Test
    public void theFrameCountIsBoundedEvenWhenEveryFrameIsTiny() {
        ParticipantOutbox outbox = outbox(Integer.MAX_VALUE);
        for (int i = 0; i < 64; i++) {
            assertTrue(outbox.offerText("x"), "frame " + i + " is still within the count bound");
        }
        assertFalse(outbox.offerText("x"),
                "64 pending frames is the limit however little each of them weighs");
    }

    @Test
    public void anOverflowedOutboxThrowsAwayWhatItHadQueued() {
        ParticipantOutbox outbox = outbox(100);
        assertTrue(outbox.offerText(frameOf(90)));
        assertFalse(outbox.offerText(frameOf(90)), "this is the frame that overflows it");
        assertEquals(1, outbox.pendingFrames(), "the earlier frame is still queued for now");

        outbox.flush();

        //a prefix delivered and then the connection cut is the one genuinely harmful outcome: a
        //state client would believe it held every patch up to some seq when it did not. So the
        //queue goes in the bin and the connection is closed instead
        assertEquals(0, outbox.pendingFrames(),
                "an overflowed outbox delivers nothing further, not even what it already held");
    }

    @Test
    public void binaryAndTextShareTheOneQueue() {
        ParticipantOutbox outbox = outbox(1000);
        assertTrue(outbox.offerBinary(new byte[600]));
        assertFalse(outbox.offerText(frameOf(600)),
                "a blob and a message compete for the same allowance");
    }
}
