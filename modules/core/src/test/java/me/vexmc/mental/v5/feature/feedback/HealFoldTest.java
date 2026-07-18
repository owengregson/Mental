package me.vexmc.mental.v5.feature.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins the heal aggregation/pacing arithmetic (F4) and the one-heart floor
 * (2.6.0). The fold is pure (long-tick primitives, no Bukkit/PE), so it is
 * asserted directly on the tick math the heal sampler drives it with: a pot
 * burst ships immediately, a sub-heart drip holds un-consumed until the sum
 * crosses {@value HealFold#MIN_SHIP_HEALTH} points and then ships once with the
 * whole accumulation, an empty accumulator never ships, and reset restores the
 * immediate-ship sentinel.
 */
class HealFoldTest {

    @Test
    void firstHealShipsImmediately() {
        HealFold fold = new HealFold();
        // A pot burst: one big delta, then the very first poll flushes it whole —
        // the Long.MIN_VALUE sentinel means the window is treated as already elapsed.
        fold.add(6.0);
        assertEquals(6.0, fold.poll(50), 1.0e-9);
        // Nothing left to ship on the next poll (the accumulator was reset).
        assertEquals(0.0, fold.poll(50), 1.0e-9);
    }

    @Test
    void subHeartDripHoldsUntilTheSumCrossesAHeart() {
        HealFold fold = new HealFold();
        // A half-heart drip is UNDER the one-heart floor: nothing ships, and the
        // accumulator is NOT consumed — the drip keeps building.
        fold.add(1.0);
        assertEquals(0.0, fold.poll(0), 1.0e-9);
        // The second half-heart crosses the floor exactly (strict <, so 2.0 ships):
        // the very first ship rides the immediate sentinel and carries the SUM.
        fold.add(1.0);
        assertEquals(2.0, fold.poll(1), 1.0e-9);
        // The window re-armed at tick 1: further drips aggregate as before...
        for (int tick = 2; tick <= 10; tick++) {
            fold.add(1.0);
            assertEquals(0.0, fold.poll(tick), 1.0e-9);
        }
        // ...and flush whole once the window elapses (nine 1-pt drips = 9.0).
        assertEquals(9.0, fold.poll(11), 1.0e-9);
    }

    @Test
    void subHeartSumHoldsEvenPastTheWindow() {
        HealFold fold = new HealFold();
        // Ship once to arm a real window, then trickle a single half-heart.
        fold.add(4.0);
        assertEquals(4.0, fold.poll(0), 1.0e-9);
        fold.add(1.0);
        // Far past the pacing window the sum is still sub-heart: the floor, not
        // the window, is what holds it — and it stays accumulated, not dropped.
        assertEquals(0.0, fold.poll(100), 1.0e-9);
        fold.add(1.0);
        assertEquals(2.0, fold.poll(101), 1.0e-9);
    }

    @Test
    void nothingShipsOnEmptySum() {
        HealFold fold = new HealFold();
        // No heal was ever accumulated: every poll reports zero, whatever the tick.
        assertEquals(0.0, fold.poll(5), 1.0e-9);
        assertEquals(0.0, fold.poll(1000), 1.0e-9);
    }

    @Test
    void resetClearsAccumulatorAndWindow() {
        HealFold fold = new HealFold();
        // Accumulate then reset: the pending sum is dropped.
        fold.add(3.0);
        fold.reset();
        assertEquals(0.0, fold.poll(0), 1.0e-9);
        // Reset also restored the immediate-ship sentinel, so a fresh heal ships at once
        // regardless of how recently the previous (now-discarded) window shipped.
        fold.add(2.0);
        assertEquals(2.0, fold.poll(0), 1.0e-9);
    }
}
