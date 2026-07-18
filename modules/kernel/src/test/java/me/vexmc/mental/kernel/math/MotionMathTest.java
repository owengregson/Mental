package me.vexmc.mental.kernel.math;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Fresh clean-room pins, every value hand-computed from the vanilla
 * recurrence {@code v ← (v − 0.08) × 0.98} (clamped at −3.92) with the
 * vanilla move-then-decay tick order: the entity moves by its CURRENT
 * velocity, then the fields decay. Arithmetic shown per case.
 */
class MotionMathTest {

    private static final double EPSILON = 1.0e-9;
    private static final double GRAVITY = 0.08;

    @Test
    void velocityDecaysByTheVanillaRecurrence() {
        // From the jump stamp 0.42:
        //   tick 1: (0.42     − 0.08) × 0.98 = 0.34 × 0.98     = 0.3332
        //   tick 2: (0.3332   − 0.08) × 0.98 = 0.2532 × 0.98   = 0.248136
        //   tick 3: (0.248136 − 0.08) × 0.98 = 0.168136 × 0.98 = 0.16477328
        assertEquals(0.3332, MotionMath.simulateVerticalVelocity(0.42, GRAVITY, 1), EPSILON);
        assertEquals(0.248136, MotionMath.simulateVerticalVelocity(0.42, GRAVITY, 2), EPSILON);
        assertEquals(0.16477328, MotionMath.simulateVerticalVelocity(0.42, GRAVITY, 3), EPSILON);
        // Zero ticks is the identity.
        assertEquals(0.42, MotionMath.simulateVerticalVelocity(0.42, GRAVITY, 0), EPSILON);
    }

    @Test
    void fallingVelocityConvergesOnAndClampsAtTerminal() {
        // The recurrence v' = 0.98v − 0.0784 has fixed point v* = −0.0784/0.02
        // = −3.92; from rest, v_n = −3.92 × (1 − 0.98^n). At n = 30 that is
        // −3.92 × (1 − 0.98^30) = −1.781701468021 (still short of terminal —
        // convergence is geometric, the clamp is for overshoot, not approach).
        assertEquals(-1.781701468021, MotionMath.simulateVerticalVelocity(0.0, GRAVITY, 30), 1.0e-9);

        // Overshoot clamps immediately: (−10 − 0.08) × 0.98 = −9.8784 → −3.92,
        // and once clamped it stays: (−3.92 − 0.08) × 0.98 = −3.92 exactly.
        assertEquals(-3.92, MotionMath.simulateVerticalVelocity(-10.0, GRAVITY, 1), EPSILON);
        assertEquals(-3.92, MotionMath.simulateVerticalVelocity(-10.0, GRAVITY, 20), EPSILON);
    }

    @Test
    void displacementMovesFirstThenDecays() {
        // Vanilla moves by the current motion BEFORE the decay, so the first
        // tick of a 0.42 jump travels the full 0.42 — the wire-visible first
        // position delta of every vanilla jump.
        assertEquals(0.42, MotionMath.distanceTraveled(0.42, 1, GRAVITY), EPSILON);
        // tick 2 adds 0.3332:            0.42 + 0.3332          = 0.7532
        assertEquals(0.7532, MotionMath.distanceTraveled(0.42, 2, GRAVITY), EPSILON);
        // tick 3 adds 0.248136:          0.7532 + 0.248136      = 1.001336
        // (a jump crosses the one-block plane on its third tick)
        assertEquals(1.001336, MotionMath.distanceTraveled(0.42, 3, GRAVITY), EPSILON);
    }

    @Test
    void jumpApexIsTheFamousTwelvePointFiveTwoTenths() {
        // Rising ticks of 0.42: the velocity sequence stays positive for six
        // steps (0.42, 0.3332, 0.248136, 0.16477328, 0.0830778144,
        // 0.003016258112) and goes negative on the seventh. Summing the six
        // positive moves: 1.252203352512 — vanilla's ~1.2522 jump height.
        assertEquals(1.252203352512, MotionMath.distanceTraveled(0.42, 6, GRAVITY), EPSILON);
        assertEquals(6, MotionMath.ticksToApex(0.42, GRAVITY));

        // The knockback base vertical 0.4 tops out one tick sooner: sequence
        // 0.3136, 0.228928, 0.14594944, 0.0646304512, then −0.015062... ≤ 0
        // at step five.
        assertEquals(5, MotionMath.ticksToApex(0.4, GRAVITY));
    }

    @Test
    void restingAndDescendingEntitiesHaveNoApex() {
        assertEquals(0, MotionMath.ticksToApex(0.0, GRAVITY));
        assertEquals(0, MotionMath.ticksToApex(-0.5, GRAVITY));
    }

    @Test
    void apexBeyondThePredictionHorizonRefuses() {
        // With gravity 0.0001 a 100-velocity launch still holds
        // 100 × 0.98^30 ≈ 54.5 upward after thirty ticks — unanswerable.
        assertEquals(-1, MotionMath.ticksToApex(100.0, 0.0001));
    }

    @Test
    void fallFromRestTakesSixTicksForOneBlock() {
        // Move-then-decay from v = 0 — the first tick moves nothing, THEN
        // gravity arrives:
        //   tick 1: pos  0.0            v → −0.0784
        //   tick 2: pos −0.0784         v → −0.155232
        //   tick 3: pos −0.233632       v → −0.23052736
        //   tick 4: pos −0.46415936     v → −0.3043168128
        //   tick 5: pos −0.7684761728   v → −0.376630476544
        //   tick 6: pos −1.145106649344 → one block fallen
        assertEquals(6, MotionMath.ticksToFall(0.0, 1.0, GRAVITY));
    }

    @Test
    void launchedFallIsOneContinuousSignedSimulation() {
        // A 0.42 launch over a 1-block drop: rises 1.252203352512 in six
        // ticks, then descends through the start plane and one block below.
        // Continuing the same signed fold: positions 1.176759... (t7),
        // 1.024424... (t8), 0.796735... (t9), 0.495200... (t10),
        // 0.121296... (t11), −0.323529... (t12), −0.837858... (t13),
        // −1.420301... (t14) → fourteen ticks, one simulation, no split
        // rise/fall estimate.
        assertEquals(14, MotionMath.ticksToFall(0.42, 1.0, GRAVITY));
    }

    @Test
    void fallBeyondThePredictionHorizonRefuses() {
        // Terminal velocity bounds thirty ticks of falling at 3.92 × 30 =
        // 117.6 blocks; ten thousand is unanswerable.
        assertEquals(-1, MotionMath.ticksToFall(0.0, 10_000.0, GRAVITY));
    }
}
