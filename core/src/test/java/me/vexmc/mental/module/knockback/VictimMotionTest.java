package me.vexmc.mental.module.knockback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins the ledger's replica of the legacy server motion fields against
 * values measured on REAL vanilla 1.7.10/1.8.9 servers (legacy-lab protocol
 * harness, 2026-06-05) and the decompiled handlers they came from.
 */
class VictimMotionTest {

    private static final double GRAVITY = 0.08;
    private static final long TICK_NANOS = 50_000_000L;
    private static final double EPSILON = 1.0e-9;

    private final VictimMotion ledger = new VictimMotion();
    private final UUID victim = UUID.randomUUID();

    // ── the physics model ────────────────────────────────────────────────

    @Test
    void airborneDecayMatchesLegacyIntegration() {
        // hand-computed: vy (v−0.08)×0.98 per tick, horizontal ×0.91
        VictimMotion.Motion motion = VictimMotion.decay(0.9, 0.5, -0.3, 3, false, GRAVITY);
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
        VictimMotion.Motion motion = VictimMotion.decay(0.9, 0.5, 0.0, 2, true, GRAVITY);
        assertEquals(0.9 * 0.546 * 0.546, motion.vx(), 1.0e-6);
        // a grounded entity parks at −gravity × 0.98, never at zero —
        // the missing −0.0784 was a measured +0.04 vertical error per hit
        assertEquals(VictimMotion.groundedEquilibrium(GRAVITY), motion.vy(), EPSILON);
    }

    @Test
    void groundedEquilibriumIsTheVanillaConstant() {
        assertEquals(-0.0784, VictimMotion.groundedEquilibrium(GRAVITY), EPSILON);
    }

    @Test
    void terminalVelocityClamps() {
        VictimMotion.Motion motion = VictimMotion.decay(0.0, -3.95, 0.0, 1, false, GRAVITY);
        assertEquals(-3.92, motion.vy(), EPSILON);
    }

    @Test
    void restThresholdZeroesDustHorizontals() {
        VictimMotion.Motion motion = VictimMotion.decay(0.004, 0.0, 0.004, 0, false, GRAVITY);
        assertEquals(0.0, motion.vx(), EPSILON);
        assertEquals(0.0, motion.vz(), EPSILON);
    }

    // ── the 1.7.10 wire decay (KnockbackDelivery.TRACKER) ────────────────

    @Test
    void trackerDecayMatchesMeasuredGroundedSprintHit() {
        // vanilla 1.7.10 shipped a standing sprint hit as (0.4914, 0.3731):
        // the formula (0.9, 0.4608) decayed one GROUND tick before the send
        VictimMotion.Motion shipped = VictimMotion.decayOnce(0.9, 0.4608, 0.0, true, GRAVITY);
        assertEquals(0.4914, shipped.vx(), 1.0e-4);
        assertEquals(0.3731, shipped.vy(), 1.0e-4);
    }

    @Test
    void trackerDecayAirborneUsesAirFriction() {
        VictimMotion.Motion shipped = VictimMotion.decayOnce(0.9, 0.35, 0.0, false, GRAVITY);
        assertEquals(0.9 * 0.91, shipped.vx(), EPSILON);
        assertEquals((0.35 - GRAVITY) * 0.98, shipped.vy(), EPSILON);
    }

    // ── the ledger state machine ─────────────────────────────────────────

    @Test
    void emptyLedgerReadsGroundedEquilibrium() {
        VictimMotion.Motion motion = ledger.current(victim, 0L, true, GRAVITY);
        assertEquals(0.0, motion.vx(), EPSILON);
        assertEquals(VictimMotion.groundedEquilibrium(GRAVITY), motion.vy(), EPSILON);
    }

    @Test
    void recordedKnockDecaysFromItsStamp() {
        ledger.record(victim, 0.9, 0.5, 0.0, false, 0L);
        VictimMotion.Motion motion = ledger.current(victim, 8 * TICK_NANOS, false, GRAVITY);
        VictimMotion.Motion expected = VictimMotion.decay(0.9, 0.5, 0.0, 8, false, GRAVITY);
        assertEquals(expected.vx(), motion.vx(), EPSILON);
        assertEquals(expected.vy(), motion.vy(), EPSILON);
    }

    @Test
    void liftoffStampsTheJumpImpulseOverTheKnock() {
        // the era movement handler treated the knock's liftoff as a jump and
        // OVERWROTE motY with 0.42 — the delivered vertical never survives
        ledger.record(victim, 0.9, 0.4608, 0.0, true, 0L);
        ledger.recordLiftoff(victim, true, false, 0.0f, TICK_NANOS, GRAVITY);

        VictimMotion.Motion motion = ledger.current(victim, TICK_NANOS, false, GRAVITY);
        assertEquals(VictimMotion.JUMP_IMPULSE, motion.vy(), EPSILON);
        // horizontal carried over (decayed one tick at the knock's ground state)
        assertEquals(0.9 * 0.546, motion.vx(), 1.0e-6);
    }

    @Test
    void jumpFreefallReproducesTheMeasuredComboBaseline() {
        // measured on vanilla 1.8.9: combo hit two ships vy 0.3478 at a
        // gap of ~10 — the 0.42 jump stamp free-fallen 9 ticks gives the
        // formula baseline (−0.3017)/2 + 0.4 + 0.1 = 0.349
        ledger.recordLiftoff(victim, true, false, 0.0f, 0L, GRAVITY);
        VictimMotion.Motion motion = ledger.current(victim, 9 * TICK_NANOS, false, GRAVITY);
        assertEquals(-0.3017, motion.vy(), 1.0e-3);
        assertEquals(0.349, motion.vy() * 0.5 + 0.4 + 0.1, 1.0e-3);
    }

    @Test
    void sprintingLiftoffAddsTheFacingPush() {
        // vanilla jump(): sprinting players push 0.2 along their facing —
        // visible in measured 1.8.9 charge combos (hit two h 0.786 ≈ 0.9
        // minus the decayed victim push)
        ledger.recordLiftoff(victim, true, true, 0.0f, 0L, GRAVITY);
        VictimMotion.Motion motion = ledger.current(victim, 0L, false, GRAVITY);
        assertEquals(0.0, motion.vx(), 1.0e-9);
        assertEquals(VictimMotion.SPRINT_JUMP_PUSH, motion.vz(), 1.0e-9);
    }

    @Test
    void landingRestampsEquilibriumAndGroundDrag() {
        ledger.record(victim, 0.9, 0.5, 0.0, false, 0L);
        ledger.recordLanding(victim, 10 * TICK_NANOS, GRAVITY);

        VictimMotion.Motion landed = ledger.current(victim, 10 * TICK_NANOS, true, GRAVITY);
        assertEquals(VictimMotion.groundedEquilibrium(GRAVITY), landed.vy(), EPSILON);
        assertEquals(0.9 * Math.pow(0.91, 10), landed.vx(), 1.0e-9);

        // post-landing decay continues at ground slip
        VictimMotion.Motion later = ledger.current(victim, 12 * TICK_NANOS, true, GRAVITY);
        assertEquals(landed.vx() * 0.546 * 0.546, later.vx(), 1.0e-9);
    }

    @Test
    void liveGroundedViewWinsOverAStaleAirborneStamp() {
        ledger.record(victim, 0.9, 0.5, 0.0, false, 0L);
        VictimMotion.Motion motion = ledger.current(victim, 4 * TICK_NANOS, true, GRAVITY);
        assertEquals(VictimMotion.groundedEquilibrium(GRAVITY), motion.vy(), EPSILON);
    }

    @Test
    void latestRecordWins() {
        ledger.record(victim, 0.9, 0.5, 0.0, false, 0L);
        ledger.record(victim, -0.2, 0.4, 0.1, false, 4 * TICK_NANOS);
        VictimMotion.Motion motion = ledger.current(victim, 4 * TICK_NANOS, false, GRAVITY);
        assertEquals(-0.2, motion.vx(), EPSILON);
        assertEquals(0.4, motion.vy(), EPSILON);
    }

    @Test
    void residualExpiresAfterTheDeadWindow() {
        ledger.record(victim, 2.0, 2.0, 2.0, false, 0L);
        assertTrue(ledger.current(victim, 200 * TICK_NANOS, false, GRAVITY).isZero());
    }

    @Test
    void forgetDropsTheResidual() {
        ledger.record(victim, 0.9, 0.5, 0.0, false, 0L);
        ledger.forget(victim);
        assertTrue(ledger.current(victim, 0L, false, GRAVITY).isZero());
    }
}
