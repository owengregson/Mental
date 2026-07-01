package me.vexmc.mental.kernel.math;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins the pure decay authority against values measured on REAL vanilla
 * 1.7.10/1.8.9 servers (legacy-lab protocol harness, 2026-06-05) and the
 * decompiled handlers they came from — the stateless subset of the original
 * VictimMotionTest (the ledger state-machine cases stay with Phase 2's
 * MotionLedger).
 */
class DecayTest {

    private static final double GRAVITY = 0.08;
    private static final double EPSILON = 1.0e-9;

    // ── the physics model ────────────────────────────────────────────────

    @Test
    void airborneDecayMatchesLegacyIntegration() {
        // hand-computed: vy (v−0.08)×0.98 per tick, horizontal ×0.91
        Decay.Motion motion = Decay.decay(0.9, 0.5, -0.3, 3, false, GRAVITY);
        assertEquals(0.9 * 0.91 * 0.91 * 0.91, motion.vx(), EPSILON);
        double vy = 0.5;
        for (int i = 0; i < 3; i++) {
            vy = (vy - GRAVITY) * 0.98;
        }
        assertEquals(vy, motion.vy(), EPSILON);
        assertEquals(-0.3 * 0.91 * 0.91 * 0.91, motion.vz(), EPSILON);
    }

    @Test
    void groundedDecayUsesSlipFrictionAndEquilibrium() {
        Decay.Motion motion = Decay.decay(0.9, 0.5, 0.0, 2, true, GRAVITY);
        assertEquals(0.9 * 0.546 * 0.546, motion.vx(), 1.0e-6);
        // a grounded entity parks at −gravity × 0.98, never at zero —
        // the missing −0.0784 was a measured +0.04 vertical error per hit
        assertEquals(Decay.groundedEquilibrium(GRAVITY), motion.vy(), EPSILON);
    }

    @Test
    void groundedEquilibriumIsTheVanillaConstant() {
        assertEquals(-0.0784, Decay.groundedEquilibrium(GRAVITY), EPSILON);
    }

    @Test
    void terminalVelocityClamps() {
        Decay.Motion motion = Decay.decay(0.0, -3.95, 0.0, 1, false, GRAVITY);
        assertEquals(-3.92, motion.vy(), EPSILON);
    }

    @Test
    void restThresholdZeroesDustHorizontals() {
        Decay.Motion motion = Decay.decay(0.004, 0.0, 0.004, 0, false, GRAVITY);
        assertEquals(0.0, motion.vx(), EPSILON);
        assertEquals(0.0, motion.vz(), EPSILON);
    }

    // ── the later-joiner wire (KnockbackDelivery.TRACKER_DECAYED) ────────

    @Test
    void trackerDecayMatchesMeasuredGroundedSprintHit() {
        // vanilla 1.7.10 shipped a standing sprint hit as (0.4914, 0.3731)
        // to victims whose connection slot ran between the hit and the
        // tracker send (joined after their attacker): the formula
        // (0.9, 0.4608) decayed one GROUND tick. Victims who joined first
        // received the full stamp — the decay is opt-in, not the default.
        Decay.Motion shipped = Decay.decayOnce(0.9, 0.4608, 0.0, true, GRAVITY);
        assertEquals(0.4914, shipped.vx(), 1.0e-4);
        assertEquals(0.3731, shipped.vy(), 1.0e-4);
    }

    @Test
    void trackerDecayAirborneUsesAirFriction() {
        Decay.Motion shipped = Decay.decayOnce(0.9, 0.35, 0.0, false, GRAVITY);
        assertEquals(0.9 * 0.91, shipped.vx(), EPSILON);
        assertEquals((0.35 - GRAVITY) * 0.98, shipped.vy(), EPSILON);
    }

    // ── slipperiness (compendium §5b — wire-measured on the real era jars) ──

    @Test
    void iceDeliveryDecayMatchesTheMeasuredWire() {
        // Real 1.7.10, packed-ice lane, hit 1: 0.4 × (0.98 × 0.91) = 0.3567.
        Decay.Motion shipped = Decay.decayOnce(0.0, 0.3608, 0.4, true, 0.98, GRAVITY);
        assertEquals(0.4 * 0.98 * 0.91, shipped.vz(), EPSILON);
        assertEquals((0.3608 - GRAVITY) * 0.98, shipped.vy(), EPSILON); // vertical is slip-free
    }
}
