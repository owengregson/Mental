package me.vexmc.mental.kernel.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the pop-off integration: position += velocity, THEN
 * vy' = (vy − gravity) × drag and horizontal ×= drag — hand-computed for the
 * shipped defaults (launch 0.25 up / 0.06 outward, gravity 0.05, drag 0.98).
 */
class IndicatorBallisticsTest {

    private static final IndicatorBallistics.Params DEFAULTS =
            new IndicatorBallistics.Params(0.25, 0.06, 0.05, 0.98);

    @Test
    void firstTicksFollowTheHandComputedTable() {
        IndicatorPlacement.Spawn spawn = new IndicatorPlacement.Spawn(0.6, 65.2, 0.0, 0.0);
        IndicatorBallistics.State s = IndicatorBallistics.launch(spawn, DEFAULTS);
        assertEquals(0.25, s.vy(), 1e-12);
        assertEquals(0.06, s.vx(), 1e-12, "outward along bearing 0 = +x");
        assertEquals(0.0, s.vz(), 1e-12);

        // Tick 1: y = 65.2 + 0.25 = 65.45; vy = (0.25 − 0.05) × 0.98 = 0.196
        s = IndicatorBallistics.step(s, DEFAULTS);
        assertEquals(65.45, s.y(), 1e-12);
        assertEquals(0.196, s.vy(), 1e-12);
        assertEquals(0.6 + 0.06, s.x(), 1e-12);
        assertEquals(0.06 * 0.98, s.vx(), 1e-12);

        // Tick 2: y = 65.45 + 0.196 = 65.646; vy = (0.196 − 0.05) × 0.98 = 0.14308
        s = IndicatorBallistics.step(s, DEFAULTS);
        assertEquals(65.646, s.y(), 1e-12);
        assertEquals(0.14308, s.vy(), 1e-12);
    }

    @Test
    void apexArrivesWithinSixTicksThenFalls() {
        IndicatorBallistics.State s = IndicatorBallistics.launch(
                new IndicatorPlacement.Spawn(0, 65.2, 0, 0), DEFAULTS);
        double lastY = s.y();
        int apexTick = -1;
        for (int t = 1; t <= 40; t++) {
            s = IndicatorBallistics.step(s, DEFAULTS);
            if (s.y() < lastY) { apexTick = t; break; }
            lastY = s.y();
        }
        assertTrue(apexTick > 2 && apexTick <= 7, "apex tick " + apexTick);
    }

    @Test
    void landedFiresWhenAtOrBelowGroundPlusEpsilon() {
        assertTrue(IndicatorBallistics.landed(
                new IndicatorBallistics.State(0, 64.04, 0, 0, -0.3, 0), 64.0));
        assertFalse(IndicatorBallistics.landed(
                new IndicatorBallistics.State(0, 64.2, 0, 0, -0.3, 0), 64.0));
    }
}
