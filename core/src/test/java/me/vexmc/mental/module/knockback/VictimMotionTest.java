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

    // ── the later-joiner wire (KnockbackDelivery.TRACKER_DECAYED) ────────

    @Test
    void trackerDecayMatchesMeasuredGroundedSprintHit() {
        // vanilla 1.7.10 shipped a standing sprint hit as (0.4914, 0.3731)
        // to victims whose connection slot ran between the hit and the
        // tracker send (joined after their attacker): the formula
        // (0.9, 0.4608) decayed one GROUND tick. Victims who joined first
        // received the full stamp — the decay is opt-in, not the default.
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
        // The horizontal carries through TWO grounded decays: the knock
        // tick's elapsed decay plus the liftoff tick's own pre-move ground
        // friction (era chains measure 0.4 × slip² × 0.91^k — real-1.7.10
        // ice hit 2 = 0.4821 = 0.4 × 0.8918² × 0.91⁷ exactly).
        assertEquals(0.9 * 0.546 * 0.546, motion.vx(), 1.0e-6);
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

    // ── The era attack-ordering contract (tick-stamped packet records) ──
    // Legacy servers processed an attack in the attacker's connection slot
    // BEFORE the victim's same-tick movement packets applied: a knock thrown
    // the instant its victim touches down reads the PRE-landing flight.
    // Measured on real vanilla 1.8.9 (both join orders): boundary combo hits
    // ship the declining ~0.25 vertical, never a grounded 0.3608 re-stamp.

    @Test
    void excludingTheLandingTickReadsThePreLandingFlight() {
        // Liftoff stamp at tick 1 (the knocked client's first risen packet),
        // landing packet at tick 10 — the boundary-hit staging.
        ledger.recordLiftoff(victim, true, false, 0.0f, TICK_NANOS, GRAVITY, VictimMotion.JUMP_IMPULSE, 1);
        ledger.recordLanding(victim, 10 * TICK_NANOS, GRAVITY, VictimMotion.DEFAULT_SLIPPERINESS, 10);
        VictimMotion.Motion asOf = ledger.currentExcludingTick(
                victim, 10, 10 * TICK_NANOS, true, GRAVITY);
        // 0.42 free-falling nine steps: the residual behind the era's
        // measured boundary vertical (0.2492 = -0.30153.../2 + 0.4).
        double expected = 0.42;
        for (int i = 0; i < 9; i++) {
            expected = (expected - GRAVITY) * 0.98;
        }
        assertEquals(expected, asOf.vy(), EPSILON);
        // The inclusive view sees the landing, like the era victim slot did
        // for any attack processed after it.
        assertEquals(VictimMotion.groundedEquilibrium(GRAVITY),
                ledger.current(victim, 10 * TICK_NANOS, true, GRAVITY).vy(), EPSILON);
    }

    @Test
    void previousTickLandingsAreNotExcluded() {
        ledger.recordLiftoff(victim, true, false, 0.0f, TICK_NANOS, GRAVITY, VictimMotion.JUMP_IMPULSE, 1);
        ledger.recordLanding(victim, 9 * TICK_NANOS, GRAVITY, VictimMotion.DEFAULT_SLIPPERINESS, 9);
        VictimMotion.Motion asOf = ledger.currentExcludingTick(
                victim, 10, 10 * TICK_NANOS, true, GRAVITY);
        // Landed a tick before the attack: the era read it grounded too.
        assertEquals(VictimMotion.groundedEquilibrium(GRAVITY), asOf.vy(), EPSILON);
    }

    @Test
    void tickAgnosticRecordsAreNeverExcluded() {
        // The per-tick sampler (packetless fake players) writes NO_TICK
        // records; the boundary read must keep today's inclusive behavior.
        ledger.recordLanding(victim, 10 * TICK_NANOS, GRAVITY);
        VictimMotion.Motion asOf = ledger.currentExcludingTick(
                victim, 10, 10 * TICK_NANOS, true, GRAVITY);
        assertEquals(VictimMotion.groundedEquilibrium(GRAVITY), asOf.vy(), EPSILON);
    }

    @Test
    void sameTickGrazePreservesTheBoundaryState() {
        // Landing then immediate re-liftoff inside one tick (a graze): the
        // boundary read still sees the pre-tick flight, not the intermediate.
        ledger.recordLiftoff(victim, true, false, 0.0f, TICK_NANOS, GRAVITY, VictimMotion.JUMP_IMPULSE, 1);
        ledger.recordLanding(victim, 10 * TICK_NANOS, GRAVITY, VictimMotion.DEFAULT_SLIPPERINESS, 10);
        ledger.recordLiftoff(victim, true, false, 0.0f, 10 * TICK_NANOS, GRAVITY, VictimMotion.JUMP_IMPULSE, 10);
        VictimMotion.Motion asOf = ledger.currentExcludingTick(
                victim, 10, 10 * TICK_NANOS, false, GRAVITY);
        double expected = 0.42;
        for (int i = 0; i < 9; i++) {
            expected = (expected - GRAVITY) * 0.98;
        }
        assertEquals(expected, asOf.vy(), EPSILON);
    }

    @Test
    void excludedFirstRecordFallsBackToNoSampleSemantics() {
        ledger.recordLiftoff(victim, true, false, 0.0f, 10 * TICK_NANOS, GRAVITY,
                VictimMotion.JUMP_IMPULSE, 10);
        VictimMotion.Motion asOf = ledger.currentExcludingTick(
                victim, 10, 10 * TICK_NANOS, true, GRAVITY);
        assertEquals(VictimMotion.groundedEquilibrium(GRAVITY), asOf.vy(), EPSILON);
    }

    @Test
    void staleBoundarySamplesAreNotExcluded() {
        // The era same-tick contract is inherently sub-tick. A frozen Folia
        // global-tick counter could stamp an OLD transition with the same value
        // the snapshot later reads, so the exclusion must also require the
        // boundary sample to be RECENT — otherwise a stale match would fire a
        // false too-sinky exclusion server-wide. A stale match reads the sample
        // inclusively instead.
        ledger.recordLiftoff(victim, true, false, 0.0f, TICK_NANOS, GRAVITY, VictimMotion.JUMP_IMPULSE, 1);
        ledger.recordLanding(victim, 10 * TICK_NANOS, GRAVITY, VictimMotion.DEFAULT_SLIPPERINESS, 10);
        // Same excludeTick=10, but read 90 ticks later (groundedNow=false so an
        // exclusion would surface the airborne liftoff, distinguishing it).
        VictimMotion.Motion asOf = ledger.currentExcludingTick(
                victim, 10, 100 * TICK_NANOS, false, GRAVITY);
        // Not excluded: reads the landing inclusively (grounded equilibrium),
        // never the long-decayed pre-landing flight.
        assertEquals(VictimMotion.groundedEquilibrium(GRAVITY), asOf.vy(), EPSILON);
    }

    // ── slipperiness, the jump-boost stamp, and the attacker self-slow ──
    // (compendium §5b — all wire-measured on the real era jars 2026-06-06)

    @Test
    void iceDeliveryDecayMatchesTheMeasuredWire() {
        // Real 1.7.10, packed-ice lane, hit 1: 0.4 × (0.98 × 0.91) = 0.3567.
        VictimMotion.Motion shipped = VictimMotion.decayOnce(0.0, 0.3608, 0.4, true, 0.98, GRAVITY);
        assertEquals(0.4 * 0.98 * 0.91, shipped.vz(), EPSILON);
        assertEquals((0.3608 - GRAVITY) * 0.98, shipped.vy(), EPSILON); // vertical is slip-free
    }

    @Test
    void groundedResidualDecaysAtTheLandedBlocksSlipperiness() {
        ledger.recordLanding(victim, 0L, GRAVITY, 0.98, VictimMotion.NO_TICK);
        ledger.record(victim, 0.0, 0.3608, 0.4, true, 0.98, 0L, VictimMotion.NO_TICK);
        VictimMotion.Motion after = ledger.current(victim, 3 * TICK_NANOS, true, GRAVITY);
        assertEquals(0.4 * Math.pow(0.98 * 0.91, 3), after.vz(), EPSILON);
    }

    @Test
    void jumpBoostRaisesTheLiftoffStamp() {
        // Real 1.8.9, Jump Boost I victim: combo hit 2 ships vy 0.3286 — the
        // 0.52 stamp (0.42 + 0.1×(0+1)) eight gravity steps later, halved
        // into the formula: residual −0.14259 → −0.14259/2 + 0.4 = 0.3287.
        ledger.recordLiftoff(victim, true, false, 0.0f, 0L, GRAVITY, 0.52, VictimMotion.NO_TICK);
        VictimMotion.Motion at8 = ledger.current(victim, 8 * TICK_NANOS, false, GRAVITY);
        double expected = 0.52;
        for (int i = 0; i < 8; i++) {
            expected = (expected - GRAVITY) * 0.98;
        }
        assertEquals(expected, at8.vy(), EPSILON);
        assertEquals(0.3287, at8.vy() / 2 + 0.4, 5.0e-4);
    }

    @Test
    void scaleHorizontalMirrorsTheAttackSelfSlow() {
        // attack() ends every bonus-knockback hit with motX *= 0.6;
        // motZ *= 0.6 on the server's fields — vertical untouched.
        ledger.record(victim, 0.3, 0.2, 0.5, false, 0L);
        ledger.scaleHorizontal(victim, 0.6, 2 * TICK_NANOS, GRAVITY);
        VictimMotion.Motion after = ledger.current(victim, 2 * TICK_NANOS, false, GRAVITY);
        assertEquals(0.3 * 0.91 * 0.91 * 0.6, after.vx(), EPSILON);
        assertEquals(0.5 * 0.91 * 0.91 * 0.6, after.vz(), EPSILON);
        double vy = 0.2;
        vy = (vy - GRAVITY) * 0.98;
        vy = (vy - GRAVITY) * 0.98;
        assertEquals(vy, after.vy(), EPSILON); // decayed to now, then untouched
    }

    @Test
    void forgetDropsTheResidual() {
        ledger.record(victim, 0.9, 0.5, 0.0, false, 0L);
        ledger.forget(victim);
        assertTrue(ledger.current(victim, 0L, false, GRAVITY).isZero());
    }
}
