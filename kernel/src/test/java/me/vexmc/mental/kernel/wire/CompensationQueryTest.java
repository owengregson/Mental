package me.vexmc.mental.kernel.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import me.vexmc.mental.kernel.math.Decay;
import me.vexmc.mental.kernel.math.MotionMath;
import me.vexmc.mental.kernel.model.KinematicState;
import me.vexmc.mental.kernel.model.PlayerView;
import me.vexmc.mental.kernel.model.TickStamp;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import org.junit.jupiter.api.Test;

/**
 * Pins the stateless per-hit vertical compensation query (B11). Every expected
 * value is hand-computed from the Phase 1 {@link MotionMath} (its vanilla
 * move-then-decay order) — a continuous signed simulation over rtt/50 ticks,
 * with the already-landed case parking at the grounded equilibrium (never zero)
 * and the double-hit guard declining recent re-hits.
 */
class CompensationQueryTest {

    private static final double GRAVITY = 0.08;
    private static final double EPSILON = 1.0e-9;

    private static PlayerView view(int noDamageTicks, KinematicState kinematics) {
        return new PlayerView(
                UUID.randomUUID(), 1, new TickStamp(0), Decay.Motion.ZERO,
                kinematics.clientOnGround(), 0.6, GRAVITY, 0.42, -1, false, false, true,
                noDamageTicks, 20, 0.0, false, KnockbackProfile.LEGACY_17, 0, kinematics);
    }

    @Test
    void onGroundVictimWithNoPredictionReturnsNull() {
        PlayerView grounded = view(0, new KinematicState(64.0, 0.0, true));
        assertNull(CompensationQuery.verticalFor(grounded, 100, -0.0784));
    }

    @Test
    void doubleHitGuardDeclinesRecentReHits() {
        // Airborne (would otherwise compensate), but noDamageTicks > 8: decline.
        PlayerView airborne = view(9, new KinematicState(70.0, 10.0, false));
        assertNull(CompensationQuery.verticalFor(airborne, 100, 0.42));
        // The boundary noDamageTicks == 8 still compensates.
        PlayerView boundary = view(8, new KinematicState(70.0, 10.0, false));
        assertEquals(
                MotionMath.simulateVerticalVelocity(0.42, GRAVITY, 2),
                CompensationQuery.verticalFor(boundary, 100, 0.42), EPSILON);
    }

    @Test
    void airborneVictimSimulatesForwardOverTheRttWindow() {
        // 100 ms rtt = 2 ticks; 10 blocks off the ground and rising, so no
        // landing within the window. Hand-computed forward simulation of 0.42:
        //   v1 = (0.42 − 0.08) × 0.98 = 0.3332
        //   v2 = (0.3332 − 0.08) × 0.98 = 0.248136
        PlayerView airborne = view(0, new KinematicState(70.0, 10.0, false));
        Double result = CompensationQuery.verticalFor(airborne, 100, 0.42);
        assertEquals(0.248136, result, EPSILON);
        assertEquals(MotionMath.simulateVerticalVelocity(0.42, GRAVITY, 2), result, EPSILON);
    }

    @Test
    void predictedLandingWithinTheWindowParksAtGroundedEquilibrium() {
        // Airborne now, but 0.5 blocks up and falling at −0.5: one tick of the
        // continuous fall covers the 0.5 blocks (displacement −0.5 ≤ −0.5), so
        // ticksToFall == 1 ≤ 4 (200 ms). The hit lands grounded → equilibrium.
        PlayerView landing = view(0, new KinematicState(64.5, 0.5, false));
        assertEquals(
                Decay.groundedEquilibrium(GRAVITY),
                CompensationQuery.verticalFor(landing, 200, -0.5), EPSILON);
    }

    @Test
    void zeroRttReturnsTheUnchangedBaseVertical() {
        // rtt < 50 ms = 0 ticks: the forward simulation is a no-op (returns baseVy).
        PlayerView airborne = view(0, new KinematicState(70.0, 10.0, false));
        assertEquals(0.42, CompensationQuery.verticalFor(airborne, 40, 0.42), EPSILON);
    }
}
