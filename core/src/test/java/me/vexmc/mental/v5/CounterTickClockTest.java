package me.vexmc.mental.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import me.vexmc.mental.kernel.model.TickStamp;
import org.junit.jupiter.api.Test;

/** Pins the two unwired TickClock implementations (the Folia counter + the Paper supplier). */
class CounterTickClockTest {

    @Test
    void counterStartsUnknownThenAdvancesFromZero() {
        CounterTickClock clock = new CounterTickClock();
        // The Folia shape: before the global-region task starts, the tick is unknown.
        assertFalse(clock.current().known());
        assertEquals(TickStamp.NO_TICK, clock.current());

        clock.advance();
        assertEquals(new TickStamp(0), clock.current());
        clock.advance();
        assertEquals(new TickStamp(1), clock.current());
        assertTrue(clock.current().known());
    }

    @Test
    void paperClockReflectsItsSupplier() {
        AtomicInteger serverTick = new AtomicInteger(41);
        PaperTickClock clock = new PaperTickClock(serverTick::get);
        assertEquals(new TickStamp(41), clock.current());
        serverTick.set(42);
        assertEquals(new TickStamp(42), clock.current());
    }
}
