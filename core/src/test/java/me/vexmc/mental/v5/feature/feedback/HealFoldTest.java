package me.vexmc.mental.v5.feature.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins the heal aggregation/pacing arithmetic (F4). The fold is pure (long-tick
 * primitives, no Bukkit/PE), so it is asserted directly on the tick math the heal
 * sampler drives it with: a pot burst ships immediately, a 1-HP-a-tick regen drip
 * aggregates to one ship of 10 per {@value HealFold#WINDOW_TICKS}-tick window, an
 * empty accumulator never ships, and reset restores the immediate-ship sentinel.
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
    void dripAggregatesToTenPerWindow() {
        HealFold fold = new HealFold();
        // The first drip ships immediately, arming the window at tick 0.
        fold.add(1.0);
        assertEquals(1.0, fold.poll(0), 1.0e-9);
        // Nine more 1-HP drips inside the window ship nothing — they accumulate.
        for (int tick = 1; tick <= 9; tick++) {
            fold.add(1.0);
            assertEquals(0.0, fold.poll(tick), 1.0e-9);
        }
        // The tenth tick is exactly WINDOW_TICKS past the last ship: the whole
        // accumulated ten HP flushes as one indicator.
        fold.add(1.0);
        assertEquals(10.0, fold.poll(10), 1.0e-9);
        // ...and the window re-arms, so the next in-window drips hold again.
        fold.add(1.0);
        assertEquals(0.0, fold.poll(11), 1.0e-9);
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
