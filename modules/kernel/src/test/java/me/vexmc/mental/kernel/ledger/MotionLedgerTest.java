package me.vexmc.mental.kernel.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.vexmc.mental.kernel.math.Decay;
import me.vexmc.mental.kernel.model.TickStamp;
import org.junit.jupiter.api.Test;

/**
 * Ports the ledger state-machine pins of the old {@code VictimMotionTest} onto
 * the single-writer, tick-driven {@link MotionLedger} API. The decay math is
 * delegated to {@link Decay} (already pinned by {@code DecayTest}); these cases
 * pin the ledger's fold — record/liftoff/landing/scale sequencing, the launch
 * tick's ground drag, the grounded-equilibrium reset (never zero), the rest
 * threshold, and the dead-after window.
 *
 * <p>The old {@code previous}-sample / {@code currentExcludingTick} exclusion
 * machinery does NOT port: exclusion is now the publication boundary (Task
 * 2.5), so {@code current()} here is simply the residual after the last
 * {@link MotionLedger#tick}. The facing-push trig is likewise not the ledger's
 * job any more — {@code GroundFsm} (Task 2.2) computes it and hands
 * {@code recordLiftoff} the pre-resolved push components; these cases pass those
 * components directly.</p>
 */
class MotionLedgerTest {

    private static final double GRAVITY = 0.08;
    private static final double EPSILON = 1.0e-9;
    private static final double SLIP = Decay.DEFAULT_SLIPPERINESS; // stone, 0.6 -> drag 0.546

    private static TickStamp t(int v) {
        return new TickStamp(v);
    }

    private static void tick(MotionLedger ledger, int from, int count) {
        for (int i = 0; i < count; i++) {
            ledger.tick(t(from + i + 1));
        }
    }

    // ── the empty ledger and the grounded equilibrium (mandate §4.2) ──────

    @Test
    void emptyLedgerReadsGroundedEquilibrium() {
        MotionLedger ledger = new MotionLedger(GRAVITY);
        Decay.Motion motion = ledger.current();
        assertEquals(0.0, motion.vx(), EPSILON);
        // A grounded entity parks at −gravity × 0.98, never at zero.
        assertEquals(Decay.groundedEquilibrium(GRAVITY), motion.vy(), EPSILON);
        assertEquals(0.0, motion.vz(), EPSILON);
        assertTrue(ledger.groundedView());
    }

    // ── record replaces all three axes; airborne decay per tick ───────────

    @Test
    void recordedKnockDecaysAcrossTicks() {
        MotionLedger ledger = new MotionLedger(GRAVITY);
        ledger.record(0.9, 0.5, 0.0, false, SLIP, t(0));
        tick(ledger, 0, 8);
        Decay.Motion expected = Decay.decay(0.9, 0.5, 0.0, 8, false, GRAVITY);
        Decay.Motion motion = ledger.current();
        assertEquals(expected.vx(), motion.vx(), EPSILON);
        assertEquals(expected.vy(), motion.vy(), EPSILON);
        assertFalse(ledger.groundedView());
    }

    @Test
    void latestRecordWins() {
        MotionLedger ledger = new MotionLedger(GRAVITY);
        ledger.record(0.9, 0.5, 0.0, false, SLIP, t(0));
        ledger.record(-0.2, 0.4, 0.1, false, SLIP, t(4));
        Decay.Motion motion = ledger.current(); // 0 ticks since the replacing record
        assertEquals(-0.2, motion.vx(), EPSILON);
        assertEquals(0.4, motion.vy(), EPSILON);
    }

    @Test
    void restThresholdZeroesDustHorizontals() {
        MotionLedger ledger = new MotionLedger(GRAVITY);
        // Sub-threshold horizontals read as exactly zero; the vertical survives.
        ledger.record(0.004, 0.5, 0.004, false, SLIP, t(0));
        Decay.Motion motion = ledger.current();
        assertEquals(0.0, motion.vx(), EPSILON);
        assertEquals(0.0, motion.vz(), EPSILON);
        assertEquals(0.5, motion.vy(), EPSILON);
    }

    // ── liftoff: the jump stamp over the knock + the launch-tick ground drag ─

    @Test
    void liftoffStampsTheJumpImpulseAndCarriesTwoGroundedDecays() {
        MotionLedger ledger = new MotionLedger(GRAVITY);
        // Grounded knock, then one elapsed grounded tick, then liftoff. The era
        // movement handler treated the knock's liftoff as a jump and OVERWROTE
        // motY with 0.42 — the delivered vertical never survives.
        ledger.record(0.9, 0.4608, 0.0, true, SLIP, t(0));
        tick(ledger, 0, 1);
        ledger.recordLiftoff(Decay.JUMP_IMPULSE, 0.0, 0.0, t(1));

        Decay.Motion motion = ledger.current();
        assertEquals(Decay.JUMP_IMPULSE, motion.vy(), EPSILON); // 0.42
        // TWO grounded decays: the elapsed tick (0.546) plus the liftoff tick's
        // own pre-move ground friction (slip × 0.91 = 0.546) — the #1 trap.
        assertEquals(0.9 * 0.546 * 0.546, motion.vx(), 1.0e-6);
    }

    @Test
    void jumpFreefallReproducesTheMeasuredComboBaseline() {
        MotionLedger ledger = new MotionLedger(GRAVITY);
        // 0.42 jump stamp free-falling: measured on vanilla 1.8.9, combo hit two
        // ships vy 0.3478 at a gap of ~10 — the stamp nine gravity steps later.
        ledger.recordLiftoff(Decay.JUMP_IMPULSE, 0.0, 0.0, t(0));
        tick(ledger, 0, 9);
        double expected = Decay.JUMP_IMPULSE;
        for (int i = 0; i < 9; i++) {
            expected = (expected - GRAVITY) * 0.98;
        }
        assertEquals(expected, ledger.current().vy(), EPSILON);
        assertEquals(-0.3017, ledger.current().vy(), 1.0e-3);
        // (−0.3017)/2 + 0.4 + 0.1 = 0.349, the measured combo baseline.
        assertEquals(0.349, ledger.current().vy() * 0.5 + 0.4 + 0.1, 1.0e-3);
    }

    @Test
    void liftoffAddsThePreResolvedFacingPush() {
        MotionLedger ledger = new MotionLedger(GRAVITY);
        // GroundFsm resolves a sprinting yaw-0 jump's facing push to (0, 0.2);
        // the ledger just adds it. (The trig itself is pinned in GroundFsmTest.)
        ledger.recordLiftoff(Decay.JUMP_IMPULSE, 0.0, Decay.SPRINT_JUMP_PUSH, t(0));
        Decay.Motion motion = ledger.current();
        assertEquals(0.0, motion.vx(), EPSILON);
        assertEquals(Decay.SPRINT_JUMP_PUSH, motion.vz(), EPSILON); // 0.2
    }

    // ── landing: seed the vertical to equilibrium, keep the decayed horizontal ─

    @Test
    void landingSeedsEquilibriumAndSwitchesToGroundDrag() {
        MotionLedger ledger = new MotionLedger(GRAVITY);
        ledger.record(0.9, 0.5, 0.0, false, SLIP, t(0));
        tick(ledger, 0, 10);
        ledger.recordLanding(t(10));

        Decay.Motion landed = ledger.current();
        // Vertical seeded to equilibrium (never zero); horizontal is the flight
        // residual (10 airborne decays), preserved through the landing.
        assertEquals(Decay.groundedEquilibrium(GRAVITY), landed.vy(), EPSILON);
        assertEquals(0.9 * Math.pow(0.91, 10), landed.vx(), 1.0e-9);
        assertTrue(ledger.groundedView());

        // Post-landing decay continues at ground slip (0.546).
        tick(ledger, 10, 2);
        assertEquals(landed.vx() * 0.546 * 0.546, ledger.current().vx(), 1.0e-9);
    }

    // ── slipperiness: the block under the feet selects the ground drag ────

    @Test
    void groundedResidualDecaysAtTheRecordedSlipperiness() {
        MotionLedger ledger = new MotionLedger(GRAVITY);
        // A grounded ice knock (slip 0.98 → drag 0.8918); the residual decays at
        // the block's slip, not stone's. Vertical stays at equilibrium.
        ledger.record(0.0, 0.3608, 0.4, true, 0.98, t(0));
        tick(ledger, 0, 3);
        assertEquals(0.4 * Math.pow(0.98 * 0.91, 3), ledger.current().vz(), EPSILON);
        assertEquals(Decay.groundedEquilibrium(GRAVITY), ledger.current().vy(), EPSILON);
    }

    @Test
    void iceChainCarriesTwoGroundedDecaysThenAirborneDecay() {
        MotionLedger ledger = new MotionLedger(GRAVITY);
        // The measured real-1.7.10 ice chain: hit-1's grounded ice residual takes
        // the knock tick's decay + the liftoff tick's launch ground drag (0.8918²)
        // then seven airborne decays (0.91⁷). Ground truth residual is
        // 0.4 × 0.8918² × 0.91⁷ ≈ 0.16439.
        ledger.record(0.4, 0.3608, 0.0, true, 0.98, t(0));
        tick(ledger, 0, 1);
        ledger.recordLiftoff(Decay.JUMP_IMPULSE, 0.0, 0.0, t(1));
        tick(ledger, 1, 7);
        double residual = 0.4 * Math.pow(0.98 * 0.91, 2) * Math.pow(0.91, 7);
        assertEquals(residual, ledger.current().vx(), EPSILON);
        // Downstream, KnockbackEngine turns that residual into hit-2's horizontal:
        // residual × friction(0.5) + base(0.4) ≈ 0.4821 (the compendium's figure).
        assertEquals(0.4821, residual * 0.5 + 0.4, 2.0e-4);
    }

    // ── the attacker self-slow (×0.6 on horizontals, vertical untouched) ──

    @Test
    void scaleHorizontalMirrorsTheAttackSelfSlow() {
        MotionLedger ledger = new MotionLedger(GRAVITY);
        ledger.record(0.3, 0.2, 0.5, false, SLIP, t(0));
        tick(ledger, 0, 2);
        ledger.scaleHorizontal(0.6);
        Decay.Motion after = ledger.current();
        assertEquals(0.3 * 0.91 * 0.91 * 0.6, after.vx(), EPSILON);
        assertEquals(0.5 * 0.91 * 0.91 * 0.6, after.vz(), EPSILON);
        // Vertical decayed to the scale point, then left untouched by the scale.
        double vy = 0.2;
        vy = (vy - GRAVITY) * 0.98;
        vy = (vy - GRAVITY) * 0.98;
        assertEquals(vy, after.vy(), EPSILON);
    }

    // ── the dead-after window (DEAD_AFTER_TICKS) ──────────────────────────

    @Test
    void residualExpiresAfterTheDeadWindow() {
        MotionLedger ledger = new MotionLedger(GRAVITY);
        ledger.record(2.0, 2.0, 2.0, false, SLIP, t(0));
        // One tick short of the window, the airborne residual is still alive:
        // its vertical is near terminal (asymptotically approaching −3.92 from
        // above), never the dead zero. This one-tick-early check is what pins
        // the window at exactly DEAD_AFTER_TICKS (a smaller constant would read
        // dead here); the old VictimMotionTest only checked the 200-tick side.
        tick(ledger, 0, MotionLedger.DEAD_AFTER_TICKS - 1);
        assertFalse(ledger.current().isZero(), "alive one tick before the window");
        assertTrue(ledger.current().vy() < -1.0, "a live falling residual, not the dead zero");
        // At the window it is dead: an airborne dead residual reads as zero.
        tick(ledger, MotionLedger.DEAD_AFTER_TICKS - 1, 1);
        assertTrue(ledger.current().isZero(), "dead at the window boundary");
    }
}
