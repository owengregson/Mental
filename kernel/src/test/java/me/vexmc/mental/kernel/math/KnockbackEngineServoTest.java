package me.vexmc.mental.kernel.math;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import me.vexmc.mental.kernel.model.EntityState;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.kernel.profile.ResistancePolicy;
import org.junit.jupiter.api.Test;

// PredictorInputs, PocketServo, Decay all live in this package (kernel.math) — no imports.

/**
 * The pocket servo's engine seam (combo-hold §3.2): σ scales the FRESH
 * horizontal knock (base push + extras) exactly like pace does — never the
 * friction-carried residual (the A3 law), never the vertical — and composes
 * multiplicatively with it. {@link PocketServoConfig#INACTIVE} is byte-identical
 * to the pace-only path (zero-touch). Every σ here is derived from the same
 * {@link PocketServo} solve the engine runs, never a magic double.
 */
class KnockbackEngineServoTest {

    private static final double EPSILON = 1.0e-9;
    private static final KnockbackProfile DEFAULTS = KnockbackProfile.LEGACY_17;
    private static final PocketServoConfig SERVO = PocketServoConfig.of(2.75, 1.0, 0.8, 1.2, 10);

    /** A fixed RNG — the coincident-direction fallback is never reached here, so it is inert. */
    private static Random rng() {
        return new Random(1L);
    }

    /** Attacker at (x, 0, z), facing +z by default, at base walk speed (attr 0.10). */
    private static EntityState attacker(double x, double z, boolean sprinting) {
        return new EntityState(x, 0, z, 0.0f, 0, 0, 0, true, sprinting, 0, 0, 0.10);
    }

    /** Grounded victim at the origin carrying (vx, vz) of residual. */
    private static EntityState victim(double vx, double vz) {
        return new EntityState(0, 0, 0, 0.0f, vx, 0, vz, true, false, 0, 0);
    }

    @Test
    void inactiveServoIsByteIdenticalToThePaceOnlyPath() {
        // A sprint hit: the pace-only overload and the servo overload with INACTIVE
        // must produce the identical vector, and comboFactor must be exactly 1.0.
        EntityState attacker = attacker(0, -3.0, true); // 3 blocks away along −z, facing +z
        EntityState victim = victim(0, 0);

        KnockbackEngine.Paced paceOnly =
                KnockbackEngine.computePaced(attacker, victim, DEFAULTS, null, rng(), false);
        KnockbackEngine.Paced servoOff = KnockbackEngine.computePaced(
                attacker, victim, DEFAULTS, null, rng(), false, PocketServoConfig.INACTIVE);

        assertEquals(paceOnly.vector().x(), servoOff.vector().x(), 0.0);
        assertEquals(paceOnly.vector().y(), servoOff.vector().y(), 0.0);
        assertEquals(paceOnly.vector().z(), servoOff.vector().z(), 0.0);
        assertEquals(1.0, servoOff.comboFactor(), 0.0);
    }

    @Test
    void servoScalesTheFreshHorizontalAndReportsTheFactor() {
        // Standing hit, zero residual: the whole horizontal is the fresh base push
        // (0.4 along −x from an attacker at +x), so the shipped horizontal is
        // exactly σ × 0.4 and the vertical (0.4) is untouched.
        EntityState attacker = attacker(3.0, 0, false); // 3 blocks along +x
        EntityState victim = victim(0, 0);

        // The σ the engine will apply — the SAME precision solve the engine runs for
        // a servo-active hit with no wired precision seam: the mandatory grounded
        // launch branch (stone slip 0.6, attr unavailable), every extra off. Not
        // hardcoded — computed from the same degraded inputs the engine builds.
        double verticalStamp = 0.4;         // base 0.4, grounded (no air), vy 0
        double freshEra = 0.4;              // the base push magnitude at pace 1.0
        PredictorInputs degraded = PredictorInputs.degraded(
                true, Decay.DEFAULT_SLIPPERINESS, EntityState.MOVE_SPEED_UNAVAILABLE);
        double expectedSigma =
                PocketServo.sigma(SERVO, degraded, 3.0, 0.0, freshEra, verticalStamp, 0.10);

        KnockbackEngine.Paced paced =
                KnockbackEngine.computePaced(attacker, victim, DEFAULTS, null, rng(), false, SERVO);

        assertEquals(expectedSigma, paced.comboFactor(), EPSILON, "the applied σ is journaled");
        // Fresh push is along −x; scaled by σ. z carries nothing; y is unscaled.
        assertEquals(-expectedSigma * 0.4, paced.vector().x(), EPSILON);
        assertEquals(0.0, paced.vector().z(), EPSILON);
        assertEquals(0.4, paced.vector().y(), EPSILON, "the servo never touches the vertical");
        assertEquals(1.0, paced.paceFactor(), 0.0, "default profile: pace off");
    }

    @Test
    void residualIsNeverScaledOnlyTheFreshContribution() {
        // A victim carrying +0.1 of +x residual: the shipped x is
        //   residual(0.1 × friction 0.5) − σ × freshPush(0.4)
        // = 0.05 − 0.4σ. The 0.05 residual is common to both σ = applied and σ = 1
        // (the A3 law); only the 0.4 fresh push scales.
        EntityState attacker = attacker(3.0, 0, false);
        EntityState victimWithResidual = victim(0.1, 0);

        KnockbackEngine.Paced active =
                KnockbackEngine.computePaced(attacker, victimWithResidual, DEFAULTS, null, rng(), false, SERVO);
        KnockbackEngine.Paced inactive = KnockbackEngine.computePaced(
                attacker, victimWithResidual, DEFAULTS, null, rng(), false, PocketServoConfig.INACTIVE);

        double sigma = active.comboFactor();
        double residual = 0.1 * 0.5; // vx × friction.x
        assertEquals(residual - sigma * 0.4, active.vector().x(), EPSILON,
                "shipped = residual (unscaled) + σ × fresh");
        // The residual carried by the INACTIVE stamp is the identical 0.05 term:
        // active.x − inactive.x is exactly the fresh delta −0.4·(σ − 1).
        assertEquals(-0.4 * (sigma - 1.0), active.vector().x() - inactive.vector().x(), EPSILON,
                "the difference is the fresh contribution only — the residual is common");
        assertEquals(inactive.vector().y(), active.vector().y(), 0.0, "vertical identical either way");
    }

    @Test
    void aFasterAttackerClosesMoreSoTheServoPushesHarder() {
        // Speed III attacker (attr 0.16) closes 1.6× as fast, so to hold the same
        // target the servo must ship the victim farther: σ is strictly larger than
        // at base speed for the same geometry. d0 = 3.7 keeps the base-speed hit
        // unclamped under the (tighter) grounded-launch drag branch, while the
        // faster chase drives σ up to the clamp — fast > slow either way.
        EntityState baseSpeed = new EntityState(3.7, 0, 0, 0.0f, 0, 0, 0, true, false, 0, 0, 0.10);
        EntityState speedThree = new EntityState(3.7, 0, 0, 0.0f, 0, 0, 0, true, false, 0, 0, 0.16);
        EntityState victim = victim(0, 0);

        double slow = KnockbackEngine
                .computePaced(baseSpeed, victim, DEFAULTS, null, rng(), false, SERVO).comboFactor();
        double fast = KnockbackEngine
                .computePaced(speedThree, victim, DEFAULTS, null, rng(), false, SERVO).comboFactor();
        org.junit.jupiter.api.Assertions.assertTrue(fast > slow,
                "a faster chase demands a stronger push to hold the pocket");
    }

    @Test
    void saturatedSprintChaseHitShipsTheEraVector() {
        // The 2026-07-09 saturation deadband end to end: a sprint-fresh hit whose
        // servo solve pins the min clamp far below the saturation floor ships the
        // FULL era stamp, not a pointless ×0.93 shave. Attacker 3 blocks along −z,
        // yaw 0 (axis-aligned sprint down +z), victim standing. The engine builds the
        // degraded grounded-stone inputs and solves σ* = 0.660121245 (< the 0.86 floor
        // at min 0.93), so the deadband declines to σ = 1: comboFactor 1.0 and a vector
        // bit-identical to the servo-inactive path (fresh × 1.0 == fresh × 1.0). The
        // era stamp is (0, 0.5, 0.9): horizontal base 0.4 + sprint extra 0.5 along +z,
        // vertical base 0.4 + sprint extra 0.1.
        PocketServoConfig shipped = PocketServoConfig.of(2.85, 1.0, 0.93, 1.35, 10);
        EntityState attacker = attacker(0, -3.0, true); // 3 blocks along −z, facing +z
        EntityState victim = victim(0, 0);

        KnockbackEngine.Paced active =
                KnockbackEngine.computePaced(attacker, victim, DEFAULTS, null, rng(), false, shipped);
        KnockbackEngine.Paced inactive = KnockbackEngine.computePaced(
                attacker, victim, DEFAULTS, null, rng(), false, PocketServoConfig.INACTIVE);

        assertEquals(1.0, active.comboFactor(), 0.0, "the saturated min-pin declines to the era σ = 1");
        assertEquals(inactive.vector().x(), active.vector().x(), 0.0, "era stamp, bit-identical to servo-off");
        assertEquals(inactive.vector().y(), active.vector().y(), 0.0);
        assertEquals(inactive.vector().z(), active.vector().z(), 0.0);
        assertEquals(0.0, active.vector().x(), EPSILON);
        assertEquals(0.5, active.vector().y(), EPSILON, "vertical untouched: base 0.4 + sprint 0.1");
        assertEquals(0.9, active.vector().z(), EPSILON, "horizontal: base 0.4 + sprint 0.5, unshaved");
    }

    @Test
    void suppressedHitReportsBothFactorsAsOne() {
        // A LEGACY resistance roll that cancels returns a null vector with both
        // factors at 1.0 (nothing was applied).
        KnockbackProfile alwaysResist = new KnockbackProfile(
                DEFAULTS.name(), DEFAULTS.displayName(), DEFAULTS.description(),
                DEFAULTS.base(), DEFAULTS.verticalMode(), DEFAULTS.extra(), DEFAULTS.wtapExtra(),
                DEFAULTS.friction(), DEFAULTS.limits(), DEFAULTS.air(), DEFAULTS.add(),
                DEFAULTS.rangeReduction(), DEFAULTS.sprintFactor(), DEFAULTS.combos(),
                DEFAULTS.meleeDelivery(), DEFAULTS.projectileDelivery(),
                ResistancePolicy.LEGACY, DEFAULTS.shieldBlockingCancels());
        EntityState resistantVictim = new EntityState(0, 0, 0, 0.0f, 0, 0, 0, true, false, 0, 1.0);
        KnockbackEngine.Paced paced = KnockbackEngine.computePaced(
                attacker(3.0, 0, false), resistantVictim, alwaysResist, null, new Random(0L), false, SERVO);
        org.junit.jupiter.api.Assertions.assertNull(paced.vector());
        assertEquals(1.0, paced.comboFactor(), 0.0);
        assertEquals(1.0, paced.paceFactor(), 0.0);
    }
}
