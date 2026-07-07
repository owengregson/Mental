package me.vexmc.mental.v5.feature.pots;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.vexmc.mental.v5.feature.pots.PotsAim.Aim;
import org.junit.jupiter.api.Test;

/**
 * Hand-computed pins for the exact-ballistic fast-pot aim (redesign 2026-07-07).
 * The load-bearing pin is {@link #landsOnPredictedFeet}: it forward-simulates the
 * real discrete potion flight ({@code vy -= 0.05; v *= 0.99; pos += v}) from the
 * returned aim and asserts the burst lands on the thrower's predicted feet — the
 * whole contract, verified against an independent simulation rather than the
 * production closed form. Two closed-form geometry pins and the budget/degenerate
 * behaviours are pinned alongside.
 */
class PotsAimTest {

    private static final double EXACT = 1.0e-6;

    /**
     * The exact server-order potion integration ({@code gravity, drag, move}),
     * independent of {@link PotsAim}'s closed form. Returns the potion position
     * after {@code ticks} ticks launched from {@code (lx,ly,lz)} at {@code aim}.
     */
    private static double[] simulate(
            double lx, double ly, double lz, Aim aim, int ticks) {
        double x = lx, y = ly, z = lz;
        double vx = aim.x(), vy = aim.y(), vz = aim.z();
        for (int k = 0; k < ticks; k++) {
            vy -= PotsAim.GRAVITY;              // gravity
            vx *= PotsAim.DRAG;                 // drag/inertia (all axes)
            vy *= PotsAim.DRAG;
            vz *= PotsAim.DRAG;
            x += vx;                            // move by the resulting delta
            y += vy;
            z += vz;
        }
        return new double[] {x, y, z};
    }

    /**
     * A resting thrower whose feet are 1.62 below the launch: the only lever is
     * straight down. With a generous cap the aim solves in one tick — closed form
     * {@code v0y = (0 - 1.62 + G(1))/H(1) = (-1.5705)/0.99 = -1.5863636}. The
     * magnitude is what the throw NEEDS (1.586), not the cap (1.6): speed is a
     * budget, not a target.
     */
    @Test
    void restingSelfThrowSolvesOneTickBelowTheCap() {
        Aim aim = PotsAim.aim(
                0.0, 1.62, 0.0,   // launch (eye)
                0.0, 0.0, 0.0,    // feet now
                0.0, 0.0, 0.0,    // thrower velocity
                1.6);             // speed cap
        assertEquals(1, aim.ticks(), "a 1.62-block drop within the cap solves in one tick");
        assertEquals(0.0, aim.x(), EXACT, "no horizontal lever");
        assertEquals(-1.5863636, aim.y(), EXACT, "the exact one-tick launch to the feet");
        assertEquals(0.0, aim.z(), EXACT, "no horizontal lever");
        assertTrue(aim.magnitude() <= 1.6 + EXACT, "the magnitude never exceeds the cap");
        assertTrue(aim.magnitude() < 1.6 - 0.01, "and it spends only what the throw needs, not the whole cap");

        double[] burst = simulate(0.0, 1.62, 0.0, aim, aim.ticks());
        assertEquals(0.0, burst[1], EXACT, "the burst lands exactly on the feet's ground level");
    }

    /**
     * A moving thrower (vx = 0.2), feet below the launch. At {@code N=1} the throw
     * needs {@code sqrt(0.202^2 + 1.586^2) = 1.599 > 1.5}, so the aim steps to
     * {@code N=2}: {@code v0x = 0.4/H(2) = 0.4/1.9701 = 0.2030353},
     * {@code v0y = (-1.62 + G(2))/H(2) = -1.471995/1.9701 = -0.747167}. The
     * two-tick flight tracks the feet's motion so the burst lands at the predicted
     * {@code (0.4, 0, 0)}.
     */
    @Test
    void movingThrowerStepsToTheFeasibleTickAndTracksTheFeet() {
        Aim aim = PotsAim.aim(
                0.0, 1.62, 0.0,
                0.0, 0.0, 0.0,
                0.2, 0.0, 0.0,
                1.5);
        assertEquals(2, aim.ticks(), "N=1 exceeds the cap, so the smallest feasible tick is 2");
        assertEquals(0.2030353, aim.x(), EXACT, "the launch that reaches the predicted feet in two ticks");
        assertEquals(-0.747167, aim.y(), EXACT, "the remaining budget goes downward");
        assertEquals(0.0, aim.z(), EXACT, "no z lever");
        assertTrue(aim.magnitude() <= 1.5 + EXACT, "within the cap");

        double[] burst = simulate(0.0, 1.62, 0.0, aim, aim.ticks());
        assertEquals(0.4, burst[0], EXACT, "the burst tracks the feet's x-motion over the flight");
        assertEquals(0.0, burst[1], EXACT, "and lands on the feet's ground level");
        assertEquals(0.0, burst[2], EXACT, "no z drift");
    }

    /**
     * Landing accuracy on arbitrary, asymmetric geometry and motion: the burst,
     * forward-simulated for the solved tick count, lands on the predicted feet
     * {@code feet + vel·ticks} — the redesign's whole point, verified end-to-end.
     */
    @Test
    void landsOnPredictedFeet() {
        double fx = 5.0, fy = 0.5, fz = 4.0;
        double vx = 0.1, vy = -0.02, vz = 0.3;
        double lx = 2.0, ly = 3.0, lz = -1.0;
        Aim aim = PotsAim.aim(lx, ly, lz, fx, fy, fz, vx, vy, vz, 2.0);
        assertTrue(aim.magnitude() <= 2.0 + EXACT, "the magnitude never exceeds the cap");

        int n = aim.ticks();
        double[] burst = simulate(lx, ly, lz, aim, n);
        assertEquals(fx + vx * n, burst[0], EXACT, "burst x = predicted feet x");
        assertEquals(fy + vy * n, burst[1], EXACT, "burst y = predicted feet y");
        assertEquals(fz + vz * n, burst[2], EXACT, "burst z = predicted feet z");
    }

    /** The magnitude never exceeds the cap, even where the target is out of budget. */
    @Test
    void magnitudeNeverExceedsTheCap() {
        // A fast thrower the potion cannot fully chase at a tight cap: the fallback
        // scales to the cap, so |v| == cap (not more), still pointed at the lead.
        Aim aim = PotsAim.aim(
                0.0, 1.62, 0.0,
                0.0, 0.0, 0.0,
                2.5, 0.0, 0.0,   // 2.5 b/t — far faster than any feasible chase
                1.0);            // tight cap
        assertTrue(aim.magnitude() <= 1.0 + EXACT, "the fallback never exceeds the cap");
        assertTrue(aim.magnitude() > 1.0 - EXACT, "and spends the whole budget chasing the lead");
    }

    /** A zero (or negative) cap cannot aim anything — a defined zero result. */
    @Test
    void nonPositiveCapYieldsZero() {
        Aim aim = PotsAim.aim(0.0, 1.62, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        assertEquals(0.0, aim.magnitude(), EXACT);
        assertEquals(0, aim.ticks());
    }

    /** The closed-form range and drop coefficients match their hand-computed values. */
    @Test
    void coefficientsMatchTheHandComputedValues() {
        assertEquals(0.99, PotsAim.rangeCoefficient(1), EXACT);
        assertEquals(1.9701, PotsAim.rangeCoefficient(2), EXACT);
        assertEquals(0.0495, PotsAim.gravityDrop(1), EXACT);
        assertEquals(0.148005, PotsAim.gravityDrop(2), EXACT);
    }
}
