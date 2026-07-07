package me.vexmc.mental.kernel.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins for the input-driven dynamic chase (servo dynamic-chase spec, 2026-07-07).
 * The load-bearing pin is {@link #closedFormMatchesTheTickSum()}: the closed-form
 * {@link DynamicChase#projectTravel} must equal an INDEPENDENT tick-by-tick sum of
 * the ramped speed across a grid of (steadySpeed, phase, ramp, window). The rest
 * pin the reset-phase deficit, the block-slow ordering (the owner's core case),
 * and the flat degrade to the linear chase.
 */
class DynamicChaseTest {

    private static final double EPSILON = 1.0e-9;

    @Test
    void closedFormMatchesTheTickSum() {
        double[] speeds = {0.2806, 0.18, 0.1};
        int[] phases = {0, 1, 3, 8, 25};
        double[] ramps = {0.3, 0.5, 0.7, 0.9};
        int[] windows = {1, 5, 10, 18};
        for (double s : speeds) {
            for (int phase : phases) {
                for (double r : ramps) {
                    for (int w : windows) {
                        assertEquals(tickSum(s, phase, r, w),
                                DynamicChase.projectTravel(s, phase, r, w), EPSILON,
                                "s=" + s + " phase=" + phase + " r=" + r + " w=" + w);
                    }
                }
            }
        }
    }

    @Test
    void freshResetClosesLessThanSettledSprint() {
        // r=0.5, w=10. A hit landed right at the reset (phase 0) prices the full ramp
        // deficit; deep into the ramp (phase 25) it is ~flat. Hand-computed phase 0:
        // deficit = 0.5^1·(1 − 0.5^10)/0.5 = 1023/1024 = 0.9990234375
        // travel  = 0.28·(10 − 0.9990234375) = 0.28·9.0009765625 = 2.5202734375
        double fresh = DynamicChase.projectTravel(0.28, 0, 0.5, 10);
        double settled = DynamicChase.projectTravel(0.28, 25, 0.5, 10);
        assertEquals(2.5202734375, fresh, 1.0e-10);
        assertTrue(fresh < settled, "a fresh reset closes less than a settled sprint");
        assertEquals(2.8, settled, 1.0e-6); // the ramp has all but decayed by phase 25
    }

    @Test
    void blockhitClosesLessThanSprintAtTheSamePhase() {
        // The owner's core case: a block-slowed attacker (lower steady speed) closes
        // strictly less than a pure sprint at the identical reset phase, so the servo
        // stops over-predicting the close for a blockhitter and mis-placing the victim.
        double sprint = DynamicChase.projectTravel(0.2806, 0, 0.5, 12);
        double blockhit = DynamicChase.projectTravel(0.18, 0, 0.5, 12);
        assertTrue(blockhit < sprint, "block-slowed chase must under-close the sprint chase");
    }

    @Test
    void invalidOrZeroRampDegradesToFlatLinearChase() {
        // r ≤ 0, r ≥ 1, or NaN -> the flat steadySpeed·window (the pre-dynamic chase).
        assertEquals(0.28 * 10, DynamicChase.projectTravel(0.28, 0, 0.0, 10), EPSILON);
        assertEquals(0.28 * 10, DynamicChase.projectTravel(0.28, 5, 1.0, 10), EPSILON);
        assertEquals(0.28 * 10, DynamicChase.projectTravel(0.28, 5, -0.2, 10), EPSILON);
        assertEquals(0.28 * 10, DynamicChase.projectTravel(0.28, 5, Double.NaN, 10), EPSILON);
    }

    @Test
    void degenerateWindowIsZero() {
        assertEquals(0.0, DynamicChase.projectTravel(0.28, 0, 0.5, 0), EPSILON);
        assertEquals(0.0, DynamicChase.projectTravel(0.28, 0, 0.5, -3), EPSILON);
        assertEquals(0.0, DynamicChase.projectRate(0.28, 0, 0.5, 0), EPSILON);
    }

    @Test
    void rateReproducesTravelOverTheWindow() {
        // The effective rate the servo re-multiplies by the same window reproduces the
        // ramped travel exactly (rate = travel / window).
        int w = 10;
        double travel = DynamicChase.projectTravel(0.2806, 0, 0.6, w);
        assertEquals(travel, DynamicChase.projectRate(0.2806, 0, 0.6, w) * w, EPSILON);
    }

    /** Independent tick-by-tick sum of the ramped speed: {@code Σ_{k=1}^{w} s·(1 − r^{phase+k})}. */
    private static double tickSum(double steadySpeed, int phase, double ramp, int window) {
        if (window <= 0) {
            return 0.0;
        }
        if (!(ramp > 0.0) || ramp >= 1.0) {
            return steadySpeed * window;
        }
        double sum = 0.0;
        for (int k = 1; k <= window; k++) {
            sum += steadySpeed * (1.0 - Math.pow(ramp, phase + k));
        }
        return sum;
    }
}
