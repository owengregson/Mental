package me.vexmc.mental.module.knockback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The decay model against closed forms of the legacy friction recurrences:
 * horizontal {@code v·dragⁿ}, vertical {@code v₀·0.98ⁿ − 3.92·(1 − 0.98ⁿ)}.
 */
class VictimMotionTest {

    private static final long TICK_NANOS = 50_000_000L;
    private static final double GRAVITY = 0.08;

    @Test
    void airborneDecayMatchesClosedForms() {
        int ticks = 8;
        VictimMotion.Motion motion = VictimMotion.decay(0.9, 0.5, -0.3, ticks, false, GRAVITY);

        double horizontalDrag = Math.pow(0.91, ticks);
        double verticalDrag = Math.pow(0.98, ticks);
        assertEquals(0.9 * horizontalDrag, motion.vx(), 1.0e-9);
        assertEquals(-0.3 * horizontalDrag, motion.vz(), 1.0e-9);
        assertEquals(0.5 * verticalDrag - 3.92 * (1.0 - verticalDrag), motion.vy(), 1.0e-9);
    }

    @Test
    void groundedDecayUsesGroundFrictionAndKillsVertical() {
        int ticks = 4;
        VictimMotion.Motion motion = VictimMotion.decay(0.9, 0.5, 0.0, ticks, true, GRAVITY);

        assertEquals(0.9 * Math.pow(0.91 * 0.6, ticks), motion.vx(), 1.0e-9);
        assertEquals(0.0, motion.vy());
        assertEquals(0.0, motion.vz());
    }

    @Test
    void residualBelowRestThresholdSnapsToZero() {
        VictimMotion.Motion motion = VictimMotion.decay(0.9, 0.0, 0.004, 10, true, GRAVITY);
        assertEquals(0.0, motion.vx()); // 0.9 × 0.546¹⁰ ≈ 0.002
        assertEquals(0.0, motion.vz());
    }

    @Test
    void fallSpeedClampsAtTerminalVelocity() {
        VictimMotion.Motion motion = VictimMotion.decay(0.0, -3.95, 0.0, 1, false, GRAVITY);
        assertEquals(-3.92, motion.vy(), 1.0e-9);
    }

    @Test
    void zeroTicksReturnsRecordedMotionVerbatim() {
        VictimMotion.Motion motion = VictimMotion.decay(0.62, 0.5, -0.62, 0, false, GRAVITY);
        assertEquals(0.62, motion.vx());
        assertEquals(0.5, motion.vy());
        assertEquals(-0.62, motion.vz());
    }

    @Test
    void currentDecaysByElapsedWallClockTicks() {
        VictimMotion ledger = new VictimMotion();
        UUID victim = UUID.randomUUID();
        long start = 1_000_000_000L;

        ledger.record(victim, 0.9, 0.5, 0.0, start);
        VictimMotion.Motion motion = ledger.current(victim, start + 8 * TICK_NANOS, false, GRAVITY);

        assertEquals(0.9 * Math.pow(0.91, 8), motion.vx(), 1.0e-9);
        assertEquals(0.5 * Math.pow(0.98, 8) - 3.92 * (1.0 - Math.pow(0.98, 8)), motion.vy(), 1.0e-9);
    }

    @Test
    void unknownVictimAndExpiredResidualReadAsZero() {
        VictimMotion ledger = new VictimMotion();
        UUID victim = UUID.randomUUID();

        assertTrue(ledger.current(victim, 0L, false, GRAVITY).isZero());

        ledger.record(victim, 2.0, 2.0, 2.0, 0L);
        assertTrue(ledger.current(victim, 60 * TICK_NANOS, false, GRAVITY).isZero());
    }

    @Test
    void laterRecordReplacesEarlierResidual() {
        VictimMotion ledger = new VictimMotion();
        UUID victim = UUID.randomUUID();

        ledger.record(victim, 0.9, 0.5, 0.0, 0L);
        ledger.record(victim, -0.2, 0.4, 0.1, 4 * TICK_NANOS);
        VictimMotion.Motion motion = ledger.current(victim, 4 * TICK_NANOS, false, GRAVITY);

        assertEquals(-0.2, motion.vx());
        assertEquals(0.4, motion.vy());
        assertEquals(0.1, motion.vz());
    }

    @Test
    void forgottenVictimReadsAsZero() {
        VictimMotion ledger = new VictimMotion();
        UUID victim = UUID.randomUUID();

        ledger.record(victim, 0.9, 0.5, 0.0, 0L);
        ledger.forget(victim);
        assertTrue(ledger.current(victim, 0L, false, GRAVITY).isZero());
    }
}
