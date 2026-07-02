package me.vexmc.mental.kernel.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import me.vexmc.mental.kernel.model.KnockbackVector;
import org.junit.jupiter.api.Test;

/**
 * New pins (the formula lived inline in ProjectileKnockbackModule's event
 * handler — no unit test covered it): 1.7.10 Punch adds {@code 0.6 × level}
 * along the arrow's horizontal flight direction plus a flat {@code 0.1}
 * vertical, additive after the base knock, re-clamped to ±3.9.
 */
class PunchMathTest {

    private static final double EPSILON = 1.0e-9;

    @Test
    void levelZeroReturnsTheBaseUntouched() {
        KnockbackVector base = new KnockbackVector(0.2, 0.4, 0.3);
        assertSame(base, PunchMath.withPunch(base, 1.0, 0.0, 0));
        assertSame(base, PunchMath.withPunch(base, 1.0, 0.0, -1));
    }

    @Test
    void punchOneAddsAlongTheNormalizedFlightPlusFlatVertical() {
        // Flight straight down +z: unit direction (0, 1); level 1 adds
        // (0 × 0.6, 0.1, 1 × 0.6) = (0, 0.1, 0.6).
        KnockbackVector base = new KnockbackVector(0.0, 0.4, 0.4);
        KnockbackVector punched = PunchMath.withPunch(base, 0.0, 2.5, 1);
        assertEquals(0.0, punched.x(), EPSILON);
        assertEquals(0.5, punched.y(), EPSILON);
        assertEquals(1.0, punched.z(), EPSILON);
    }

    @Test
    void punchTwoDoublesTheHorizontalButNotTheVertical() {
        // The vertical is a flat 0.1 — never per level (the era addition).
        KnockbackVector base = new KnockbackVector(0.0, 0.4, 0.4);
        KnockbackVector punched = PunchMath.withPunch(base, 0.0, 1.0, 2);
        assertEquals(0.5, punched.y(), EPSILON);
        assertEquals(0.4 + 2 * 0.6, punched.z(), EPSILON);
    }

    @Test
    void diagonalFlightDistributesByDirection() {
        // Flight (3, 4): |h| = 5, unit (0.6, 0.8); level 1 adds
        // (0.6 × 0.6, 0.1, 0.8 × 0.6) = (0.36, 0.1, 0.48).
        KnockbackVector base = new KnockbackVector(0.1, 0.2, 0.1);
        KnockbackVector punched = PunchMath.withPunch(base, 3.0, 4.0, 1);
        assertEquals(0.1 + 0.36, punched.x(), EPSILON);
        assertEquals(0.2 + 0.1, punched.y(), EPSILON);
        assertEquals(0.1 + 0.48, punched.z(), EPSILON);
    }

    @Test
    void degenerateVerticalFlightAddsNothing() {
        // An arrow falling straight down has no horizontal direction to punch
        // along — the base ships unchanged.
        KnockbackVector base = new KnockbackVector(0.2, 0.4, 0.3);
        assertSame(base, PunchMath.withPunch(base, 0.0, 0.0, 2));
        assertSame(base, PunchMath.withPunch(base, 1.0e-5, 0.0, 2));
    }

    @Test
    void resultReclampsToTheLegacyPacketLimit() {
        // Punch X on a near-clamp base cannot exceed ±3.9.
        KnockbackVector base = new KnockbackVector(0.0, 3.85, 3.85);
        KnockbackVector punched = PunchMath.withPunch(base, 0.0, 1.0, 10);
        assertEquals(3.9, punched.y(), EPSILON);   // 3.85 + 0.1 → clamped
        assertEquals(3.9, punched.z(), EPSILON);   // 3.85 + 6.0 → clamped
    }
}
