package me.vexmc.mental.kernel.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PositionRingTest {

    private static final long TICK = 50_000_000L;

    @Test
    void samplesAroundReturnsOnlyTheWindow() {
        PositionRing history = new PositionRing();
        UUID player = UUID.randomUUID();
        for (int tick = 0; tick < 10; tick++) {
            history.record(player, 0, 0, tick, tick * TICK);
        }

        // ±1.5 ticks around tick 5 → ticks 4, 5, 6.
        List<PositionRing.Sample> window =
                history.samplesAround(player, 5 * TICK, TICK * 3 / 2);
        assertEquals(3, window.size());
        assertTrue(window.stream().allMatch(s -> s.z() >= 4 && s.z() <= 6));
    }

    @Test
    void ringWrapsKeepingTheNewestSamples() {
        PositionRing history = new PositionRing();
        UUID player = UUID.randomUUID();
        for (int tick = 0; tick < 100; tick++) {
            history.record(player, 0, 0, tick, tick * TICK);
        }

        // The oldest surviving sample is tick 60 (capacity 40).
        assertTrue(history.samplesAround(player, 30 * TICK, TICK).isEmpty(),
                "overwritten samples must be gone");
        List<PositionRing.Sample> newest = history.samplesAround(player, 99 * TICK, TICK / 2);
        assertEquals(1, newest.size());
        assertEquals(99, newest.get(0).z());
    }

    @Test
    void untrackedAndForgottenPlayersHaveNoSamples() {
        PositionRing history = new PositionRing();
        UUID player = UUID.randomUUID();
        assertTrue(history.samplesAround(player, 0, TICK).isEmpty());

        history.record(player, 1, 2, 3, TICK);
        assertEquals(1, history.samplesAround(player, TICK, TICK).size());

        history.forget(player);
        assertTrue(history.samplesAround(player, TICK, TICK).isEmpty());

        history.record(player, 1, 2, 3, TICK);
        history.clear();
        assertTrue(history.samplesAround(player, TICK, TICK).isEmpty());
    }
}
