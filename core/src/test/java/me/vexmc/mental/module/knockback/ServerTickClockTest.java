package me.vexmc.mental.module.knockback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;

/**
 * Pins the netty-readable server-tick surrogate behind the era attack-ordering
 * exclusion.
 *
 * <p>On Paper it is the authoritative {@code Bukkit.getCurrentTick()}; on Folia,
 * where that throws off a region thread, a plugin global counter stands in. The
 * load-bearing property is that the Folia counter starts at
 * {@link VictimMotion#NO_TICK}: both the packet stamp and the snapshot read
 * consult it, so a counter that never advanced would otherwise make both sites
 * read the SAME value and the exclusion fire universally and permanently — a
 * server-wide too-sinky regression. Starting at NO_TICK makes a stuck counter
 * degrade to the inclusive (no-exclusion) view instead.</p>
 */
class ServerTickClockTest {

    private static IntSupplier regionClockThatThrows() {
        return () -> {
            throw new AssertionError("the Folia clock must never read Bukkit.getCurrentTick()");
        };
    }

    @Test
    void paperReadsTheAuthoritativeServerTick() {
        assertEquals(123, new ServerTickClock(false, () -> 123).currentTick());
    }

    @Test
    void foliaIsNoTickBeforeTheFirstGlobalTick() {
        // A never-started (or not-yet-ticked) counter must read NO_TICK so the
        // exclusion stays inert, not a stuck value that excludes everything.
        assertEquals(VictimMotion.NO_TICK,
                new ServerTickClock(true, regionClockThatThrows()).currentTick());
    }

    @Test
    void foliaFirstTickIsZeroThenCountsUp() {
        ServerTickClock clock = new ServerTickClock(true, regionClockThatThrows());
        clock.tick();
        assertEquals(0, clock.currentTick());
        clock.tick();
        assertEquals(1, clock.currentTick());
        clock.tick();
        assertEquals(2, clock.currentTick());
    }

    @Test
    void foliaNeverReadsTheRegionClock() {
        ServerTickClock clock = new ServerTickClock(true, regionClockThatThrows());
        clock.tick();
        assertEquals(0, clock.currentTick());
    }
}
