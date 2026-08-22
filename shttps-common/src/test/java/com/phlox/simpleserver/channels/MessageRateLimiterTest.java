package com.phlox.simpleserver.channels;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The bucket itself, on a clock this test moves by hand - the alternative is sleeping through real
 * seconds to watch permits come back, which is both slow and flaky.
 * {@code ChannelsRateLimitTest} covers what a connection that exceeds it is told.
 */
public class MessageRateLimiterTest {
    @Test
    public void aBurstUpToTheLimitIsAllowedAndTheNextMessageIsNot() {
        MessageRateLimiter limiter = new MessageRateLimiter(5, 1000L);

        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire(1000L), "message " + (i + 1) + " of a limit of 5 was refused");
        }
        assertFalse(limiter.tryAcquire(1000L), "the sixth message in the same instant is over the limit");
    }

    @Test
    public void permitsComeBackAsTimePasses() {
        MessageRateLimiter limiter = new MessageRateLimiter(5, 1000L);
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire(1000L);
        }

        assertFalse(limiter.tryAcquire(1100L), "a fifth of a second is not enough for a whole permit");
        assertTrue(limiter.tryAcquire(1200L), "a permit is due after a fifth of a second at 5/s");
        assertFalse(limiter.tryAcquire(1200L), "and only the one");

        //a client that waited out a whole second is back to a full bucket, not to a larger one
        assertTrue(limiter.tryAcquire(5000L));
        for (int i = 0; i < 4; i++) {
            assertTrue(limiter.tryAcquire(5000L));
        }
        assertFalse(limiter.tryAcquire(5000L), "the bucket does not grow past the limit while idle");
    }

    @Test
    public void aSteadySenderWithinTheLimitIsNeverRefused() {
        MessageRateLimiter limiter = new MessageRateLimiter(20, 0L);

        //one message every 50ms is exactly 20/s, sent evenly rather than in a burst
        for (int i = 0; i < 200; i++) {
            assertTrue(limiter.tryAcquire(i * 50L),
                    "a client sending steadily at the limit was refused at message " + i);
        }
    }

    @Test
    public void aLimitOfZeroOrLessTurnsTheLimitOff() {
        for (int permitsPerSecond : new int[]{0, -1}) {
            MessageRateLimiter limiter = new MessageRateLimiter(permitsPerSecond, 0L);
            for (int i = 0; i < 1000; i++) {
                assertTrue(limiter.tryAcquire(0L),
                        "a limit of " + permitsPerSecond + " must let everything through, not nothing");
            }
        }
    }

    @Test
    public void aClockThatWentBackwardsDoesNotDrainTheBucket() {
        MessageRateLimiter limiter = new MessageRateLimiter(5, 10_000L);
        assertTrue(limiter.tryAcquire(10_000L));

        //an NTP step or a manual change; the remaining permits have to still be there
        for (int i = 0; i < 4; i++) {
            assertTrue(limiter.tryAcquire(4_000L), "time running backwards took a permit away");
        }
        assertFalse(limiter.tryAcquire(4_000L));
    }
}
