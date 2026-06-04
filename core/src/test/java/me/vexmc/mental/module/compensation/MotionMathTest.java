package me.vexmc.mental.module.compensation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Expectations hand-traced from vanilla's loop {@code v ← (v − 0.08) × 0.98}.
 */
class MotionMathTest {

    private static final double EPSILON = 1.0e-9;
    private static final double GRAVITY = 0.08;

    @Test
    void simulatesVanillaVerticalDecay() {
        assertEquals(0.3136, MotionMath.simulateVerticalVelocity(0.4, GRAVITY, 1), EPSILON);
        assertEquals(0.228928, MotionMath.simulateVerticalVelocity(0.4, GRAVITY, 2), EPSILON);
        assertEquals(0.14594944, MotionMath.simulateVerticalVelocity(0.4, GRAVITY, 3), EPSILON);
    }

    @Test
    void clampsAtTerminalVelocityWhileFalling() {
        // From rest the recurrence converges toward -3.92 geometrically:
        // v_n = -3.92 × (1 − 0.98^n).
        double expected = -MotionMath.TERMINAL_VELOCITY * (1 - Math.pow(MotionMath.DRAG_MULTIPLIER, 30));
        assertEquals(expected, MotionMath.simulateVerticalVelocity(0.0, GRAVITY, 30), EPSILON);

        // Extreme downward motion clamps to the terminal floor immediately.
        double longFall = MotionMath.simulateVerticalVelocity(-10.0, GRAVITY, 5);
        assertEquals(-MotionMath.TERMINAL_VELOCITY, longFall, EPSILON);
    }

    @Test
    void apexOfAKnockbackArcIsFiveTicks() {
        assertEquals(5, MotionMath.ticksToApex(0.4, GRAVITY));
        assertEquals(0, MotionMath.ticksToApex(0.0, GRAVITY));
        assertEquals(0, MotionMath.ticksToApex(-1.0, GRAVITY));
    }

    @Test
    void apexBeyondTheCapRefusesPrediction() {
        assertEquals(-1, MotionMath.ticksToApex(100.0, 0.0001));
    }

    @Test
    void distanceTraveledAccumulatesPerTickMotion() {
        assertEquals(0.4, MotionMath.distanceTraveled(0.4, 1, GRAVITY), EPSILON);
        assertEquals(0.7136, MotionMath.distanceTraveled(0.4, 2, GRAVITY), EPSILON);
        assertEquals(1.1531078912, MotionMath.distanceTraveled(0.4, 5, GRAVITY), EPSILON);
    }

    @Test
    void fallTimeFromRestOverOneBlockIsFiveTicks() {
        assertEquals(5, MotionMath.ticksToFall(0.0, 1.0, GRAVITY));
    }

    @Test
    void fallBeyondTheCapRefusesPrediction() {
        assertEquals(-1, MotionMath.ticksToFall(0.0, 10_000.0, GRAVITY));
    }
}
