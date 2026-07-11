package me.vexmc.mental.kernel.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Pins the front-half ring placement: bearing within ±90° of the
 * victim→attacker azimuth, radius exact, height jitter within its band.
 */
class IndicatorPlacementTest {

    @Test
    void spawnSitsOnTheRingAtTheConfiguredRadius() {
        Random random = new Random(42);
        IndicatorPlacement.Spawn spawn = IndicatorPlacement.place(
                0.0, 64.0, 0.0,   // victim feet
                3.0, 0.0,          // attacker due +x
                0.6, 1.2, 0.3, random);
        double dx = spawn.x();
        double dz = spawn.z();
        assertEquals(0.6, Math.hypot(dx, dz), 1e-9, "radius");
    }

    @Test
    void bearingStaysInTheFrontHalfTowardTheAttacker() {
        // Attacker due +x from the victim: azimuth 0. Front half = (-90°, +90°),
        // i.e. the spawn's x offset is strictly positive across many draws.
        Random random = new Random(7);
        for (int i = 0; i < 200; i++) {
            IndicatorPlacement.Spawn spawn = IndicatorPlacement.place(
                    0.0, 64.0, 0.0, 5.0, 0.0, 0.6, 1.2, 0.3, random);
            assertTrue(spawn.x() > 0.0, "front half draw " + i + " x=" + spawn.x());
        }
    }

    @Test
    void heightIsChestPlusBoundedJitter() {
        Random random = new Random(7);
        for (int i = 0; i < 200; i++) {
            IndicatorPlacement.Spawn spawn = IndicatorPlacement.place(
                    0.0, 64.0, 0.0, 5.0, 0.0, 0.6, 1.2, 0.3, random);
            assertTrue(spawn.y() >= 64.0 + 1.2 - 0.3 - 1e-9, "low bound " + spawn.y());
            assertTrue(spawn.y() <= 64.0 + 1.2 + 0.3 + 1e-9, "high bound " + spawn.y());
        }
    }

    @Test
    void degenerateZeroDistanceAttackerStillPlaces() {
        // Attacker exactly on the victim column: azimuth is undefined; the
        // implementation must substitute bearing 0 rather than NaN.
        Random random = new Random(1);
        IndicatorPlacement.Spawn spawn = IndicatorPlacement.place(
                0.0, 64.0, 0.0, 0.0, 0.0, 0.6, 1.2, 0.3, random);
        assertEquals(0.6, Math.hypot(spawn.x(), spawn.z()), 1e-9);
        assertTrue(Double.isFinite(spawn.bearing()));
    }
}
