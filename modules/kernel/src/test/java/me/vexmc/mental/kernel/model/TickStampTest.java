package me.vexmc.mental.kernel.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the tick-index value type — the only clock currency in the delivery
 * core. A stamp expresses "unknown" without a magic int, and recency is a
 * pure tick-delta compare (no wall clock).
 */
class TickStampTest {

    @Test
    void noTickIsNotKnown() {
        assertFalse(TickStamp.NO_TICK.known());
        assertTrue(new TickStamp(0).known());
        assertTrue(new TickStamp(Integer.MAX_VALUE).known());
    }

    @Test
    void recentAtIsFalseWhenEitherSideUnknown() {
        TickStamp known = new TickStamp(10);
        assertFalse(known.recentAt(TickStamp.NO_TICK, 4), "unknown now cannot be recent");
        assertFalse(TickStamp.NO_TICK.recentAt(known, 4), "unknown this cannot be recent");
        assertFalse(TickStamp.NO_TICK.recentAt(TickStamp.NO_TICK, 4));
    }

    @Test
    void recentAtIsTrueAtDistanceZeroAndAtTheBound() {
        TickStamp then = new TickStamp(100);
        assertTrue(then.recentAt(new TickStamp(100), 4), "distance 0 is recent");
        assertTrue(then.recentAt(new TickStamp(104), 4), "distance 4 (inclusive) is recent");
    }

    @Test
    void recentAtIsFalseBeyondTheBound() {
        TickStamp then = new TickStamp(100);
        assertFalse(then.recentAt(new TickStamp(105), 4), "distance 5 exceeds the window");
    }

    @Test
    void recentAtIsFalseForFutureStamps() {
        // now < this: a stamp from the future is never "recent" (delta < 0).
        TickStamp future = new TickStamp(100);
        assertFalse(future.recentAt(new TickStamp(99), 4));
    }
}
