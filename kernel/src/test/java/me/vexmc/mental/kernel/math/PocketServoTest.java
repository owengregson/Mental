package me.vexmc.mental.kernel.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins for the pocket servo's exact inverse solve (combo-hold §3.2). The load-
 * bearing test is {@link #unclampedSolveLandsExactlyOnTarget()}: for a grid of
 * (d0, residualCarry, vertical stamp, attacker speed) the σ the solve returns,
 * fed through an INDEPENDENT tick-by-tick era flight simulation, lands the
 * victim within 1e-9 of the target. The predictor's geometric-sum and air-time
 * pieces are hand-pinned separately so the closed form can never drift from the
 * simulation it inverts.
 */
class PocketServoTest {

    private static final double EPSILON = 1.0e-9;

    /** The default active servo: target 2.75, gain 1.0, clamps [0.8, 1.2], window 10. */
    private static final PocketServoConfig SERVO = PocketServoConfig.of(2.75, 1.0, 0.8, 1.2, 10);

    /* ── the predictor's pieces (independent of the solve) ─────────────────── */

    @Test
    void airTimeMatchesAnIndependentTickSim() {
        // Re-simulate the launch from ground level and count airborne ticks; the
        // production air-time must agree exactly.
        for (double stamp : new double[] {0.3608, 0.4, 0.4607, 0.5, 0.7}) {
            assertEquals(simulateAirTime(stamp), PocketServo.airTime(stamp),
                    "air time for vertical stamp " + stamp);
        }
        // A non-positive (grounded/descending) stamp is airborne for zero ticks.
        assertEquals(0, PocketServo.airTime(0.0));
        assertEquals(0, PocketServo.airTime(-0.1));
        // A standing knock (0.4) hand-count: 11 airborne ticks.
        assertEquals(11, PocketServo.airTime(0.4));
    }

    @Test
    void effectiveWindowTruncatesAtTouchdown() {
        // 0.4 stays up 11 ticks; the window 10 caps it at 10.
        assertEquals(10, PocketServo.effectiveWindow(10, 0.4));
        // A very low stamp lands before the window closes, truncating it.
        int lowStampAir = PocketServo.airTime(0.2);
        assertTrue(lowStampAir < 10, "a 0.2 stamp lands before 10 ticks");
        assertEquals(lowStampAir, PocketServo.effectiveWindow(10, 0.2));
    }

    @Test
    void dragSumIsTheGeometricSumOfEraAirDrag() {
        // Σ_{k=0}^{w-1} 0.91^k, hand-checked against the closed form for a few w.
        assertEquals(0.0, PocketServo.dragSum(0), 0.0);
        assertEquals(1.0, PocketServo.dragSum(1), EPSILON);
        assertEquals(1.0 + 0.91, PocketServo.dragSum(2), EPSILON);
        assertEquals(bruteDragSum(10), PocketServo.dragSum(10), EPSILON);
        // The closed form and a straight loop agree for every window in range.
        for (int window = 0; window <= 20; window++) {
            assertEquals(bruteDragSum(window), PocketServo.dragSum(window), EPSILON,
                    "dragSum(" + window + ")");
        }
    }

    @Test
    void chaseFactorNormalizesOnTheWalkBaseline() {
        assertEquals(1.0, PocketServo.chaseFactor(0.10), EPSILON);   // base speed
        assertEquals(1.6, PocketServo.chaseFactor(0.16), EPSILON);   // Speed III walk-normalized
        assertEquals(0.7, PocketServo.chaseFactor(0.07), EPSILON);   // Slowness II
        assertEquals(1.0, PocketServo.chaseFactor(-1.0), EPSILON);   // unavailable ⇒ baseline
    }

    /* ── the exact-inverse property ────────────────────────────────────────── */

    @Test
    void unclampedSolveLandsExactlyOnTarget() {
        double[] separations = {2.4, 2.842294, 3.0, 3.2};
        double[] residuals = {0.0, 0.1, 0.25};
        double[] verticalStamps = {0.3608, 0.4, 0.4607};
        double[] attrs = {0.10, 0.13, 0.16};
        double freshEra = 0.4;

        int checked = 0;
        for (double d0 : separations) {
            for (double residual : residuals) {
                for (double stamp : verticalStamps) {
                    for (double attr : attrs) {
                        double sigma = PocketServo.sigma(SERVO, d0, residual, freshEra, attr, stamp);
                        // Skip the clamped grid points; the exact-landing property is
                        // only claimed unclamped (clamped cases are pinned below).
                        if (sigma <= SERVO.min() + EPSILON || sigma >= SERVO.max() - EPSILON) {
                            continue;
                        }
                        double landed = simulateLanding(d0, residual, sigma, freshEra, attr, stamp);
                        assertEquals(SERVO.target(), landed, EPSILON,
                                "σ must land the victim on target for d0=" + d0 + " residual="
                                        + residual + " stamp=" + stamp + " attr=" + attr);
                        checked++;
                    }
                }
            }
        }
        assertTrue(checked > 0, "the grid must exercise at least one unclamped solve");
    }

    @Test
    void tooCloseClampsToTheUpperBound() {
        // A victim already inside the target with a slow chase needs a strong push
        // out, past the clamp: σ pins to max (1.2).
        double sigma = PocketServo.sigma(SERVO, 1.5, 0.0, 0.4, 0.10, 0.4);
        assertEquals(1.2, sigma, 0.0, "an over-solve clamps to max exactly");
    }

    @Test
    void tooFarClampsToTheLowerBound() {
        // A victim already past the target needs the knock softened below the
        // clamp: σ pins to min (0.8).
        double sigma = PocketServo.sigma(SERVO, 4.8, 0.0, 0.4, 0.10, 0.4);
        assertEquals(0.8, sigma, 0.0, "an under-solve clamps to min exactly");
    }

    @Test
    void gainBlendsTowardTheExactSolve() {
        // gain 0 => σ is exactly 1.0 (no shaping) regardless of geometry.
        PocketServoConfig noGain = PocketServoConfig.of(2.75, 0.0, 0.8, 1.2, 10);
        assertEquals(1.0, PocketServo.sigma(noGain, 3.2, 0.0, 0.4, 0.10, 0.4), 0.0);
        // gain 0.5 sits halfway between 1.0 and the full σ* (before clamping).
        double full = PocketServo.sigma(SERVO, 3.0, 0.0, 0.4, 0.10, 0.4);
        PocketServoConfig halfGain = PocketServoConfig.of(2.75, 0.5, 0.8, 1.2, 10);
        double half = PocketServo.sigma(halfGain, 3.0, 0.0, 0.4, 0.10, 0.4);
        assertEquals(1.0 + 0.5 * (full - 1.0), half, EPSILON);
    }

    @Test
    void inactiveOrNoLeverReturnsExactlyOne() {
        // Inactive config: byte-exact 1.0.
        assertEquals(1.0, PocketServo.sigma(PocketServoConfig.INACTIVE, 3.0, 0.1, 0.4, 0.13, 0.4), 0.0);
        // No fresh horizontal to scale: 1.0 (the servo has no lever).
        assertEquals(1.0, PocketServo.sigma(SERVO, 3.0, 0.1, 0.0, 0.13, 0.4), 0.0);
        // A non-positive vertical stamp => zero air time => zero window => 1.0.
        assertEquals(1.0, PocketServo.sigma(SERVO, 3.0, 0.1, 0.4, 0.13, 0.0), 0.0);
    }

    /* ── independent simulators (never call the production closed form) ────── */

    /** Tick-by-tick era flight: where the victim ends up relative to the chasing attacker. */
    private static double simulateLanding(
            double d0, double residualCarry, double sigma, double freshEra,
            double attr, double verticalStamp) {
        int window = Math.min(10, simulateAirTime(verticalStamp));
        double velocity = residualCarry + sigma * freshEra;
        double separation = d0;
        for (int tick = 0; tick < window; tick++) {
            separation += velocity;         // move by the current velocity
            velocity *= Decay.AIR_DRAG;     // then decay it
        }
        double chase = PocketServo.SPRINT_GROUND_SPEED * (attr / PocketServo.WALK_BASELINE) * window;
        return separation - chase;
    }

    /** Independent air-time sim (mirrors the vanilla vertical integration order). */
    private static int simulateAirTime(double verticalStamp) {
        if (!(verticalStamp > 0.0)) {
            return 0;
        }
        double y = 0.0;
        double vy = verticalStamp;
        int ticks = 0;
        while (ticks < 5000) {
            y += vy;
            ticks++;
            if (y <= 0.0) {
                break;
            }
            vy = (vy - Decay.DEFAULT_GRAVITY) * Decay.VERTICAL_DRAG;
        }
        return ticks;
    }

    private static double bruteDragSum(int window) {
        double sum = 0.0;
        double term = 1.0;
        for (int k = 0; k < window; k++) {
            sum += term;
            term *= Decay.AIR_DRAG;
        }
        return sum;
    }
}
