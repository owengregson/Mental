package me.vexmc.mental.kernel.wire;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CpsLimiterTest {

    private final CpsLimiter limiter = new CpsLimiter();
    private final UUID player = UUID.randomUUID();

    @Test
    void capZeroNeverLimits() {
        for (int i = 0; i < 100; i++) {
            assertTrue(limiter.tryAcquire(player, 0, i));
        }
    }

    @Test
    void deniesHitsBeyondCapInsideTheWindow() {
        assertTrue(limiter.tryAcquire(player, 2, 0));
        assertTrue(limiter.tryAcquire(player, 2, 100));
        assertFalse(limiter.tryAcquire(player, 2, 200));
        assertFalse(limiter.tryAcquire(player, 2, 999));
    }

    @Test
    void slidesTheWindowAfterOneSecond() {
        assertTrue(limiter.tryAcquire(player, 2, 0));
        assertTrue(limiter.tryAcquire(player, 2, 100));
        assertFalse(limiter.tryAcquire(player, 2, 500));
        assertTrue(limiter.tryAcquire(player, 2, 1000)); // oldest (0) is now a full window old
        assertFalse(limiter.tryAcquire(player, 2, 1050)); // next-oldest (100) is not
        assertTrue(limiter.tryAcquire(player, 2, 1100));
    }

    @Test
    void capacityChangeResetsTheWindow() {
        assertTrue(limiter.tryAcquire(player, 1, 0));
        assertFalse(limiter.tryAcquire(player, 1, 10));
        assertTrue(limiter.tryAcquire(player, 3, 20));
        assertTrue(limiter.tryAcquire(player, 3, 30));
        assertTrue(limiter.tryAcquire(player, 3, 40));
        assertFalse(limiter.tryAcquire(player, 3, 50));
    }

    @Test
    void playersAreIndependent() {
        UUID other = UUID.randomUUID();
        assertTrue(limiter.tryAcquire(player, 1, 0));
        assertTrue(limiter.tryAcquire(other, 1, 0));
        assertFalse(limiter.tryAcquire(player, 1, 1));
        assertFalse(limiter.tryAcquire(other, 1, 1));
    }
}
