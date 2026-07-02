package me.vexmc.mental.kernel.math;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * New pins (no unit test covered the trig in core — it was exercised only
 * through live suites): hand-computed from the vanilla formula
 * {@code atan2(Δz, Δx) × 180/π − victimYaw}.
 */
class HurtYawTest {

    private static final float EPSILON = 1.0e-6f;

    @Test
    void cardinalDirectionsMatchAtanTwo() {
        // Attacker due +x of the victim: atan2(0, 1) = 0 → 0°.
        assertEquals(0.0f, HurtYaw.hurtYaw(1, 0, 0, 0, 0.0f), EPSILON);
        // Attacker due +z: atan2(1, 0) = π/2 → 90°.
        assertEquals(90.0f, HurtYaw.hurtYaw(0, 1, 0, 0, 0.0f), EPSILON);
        // Attacker due −x: atan2(0, −1) = π → 180°.
        assertEquals(180.0f, HurtYaw.hurtYaw(-1, 0, 0, 0, 0.0f), EPSILON);
        // Attacker due −z: atan2(−1, 0) = −π/2 → −90°.
        assertEquals(-90.0f, HurtYaw.hurtYaw(0, -1, 0, 0, 0.0f), EPSILON);
    }

    @Test
    void diagonalIsFortyFiveDegrees() {
        // atan2(1, 1) = π/4 → 45°.
        assertEquals(45.0f, HurtYaw.hurtYaw(1, 1, 0, 0, 0.0f), EPSILON);
    }

    @Test
    void victimYawSubtractsFromTheWorldAngle() {
        // Same +z attacker, victim facing 90°: 90 − 90 = 0° (hurt from dead ahead).
        assertEquals(0.0f, HurtYaw.hurtYaw(0, 1, 0, 0, 90.0f), EPSILON);
        // Victim facing −45°: 90 − (−45) = 135°.
        assertEquals(135.0f, HurtYaw.hurtYaw(0, 1, 0, 0, -45.0f), EPSILON);
    }

    @Test
    void victimPositionShiftsTheDelta() {
        // Attacker at (3, 4), victim at (3, 3): Δ = (0, 1) → 90°.
        assertEquals(90.0f, HurtYaw.hurtYaw(3, 4, 3, 3, 0.0f), EPSILON);
    }
}
