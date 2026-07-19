package me.vexmc.mental.v5.feature.knockback;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Hand-computed pins for the pure Combat Test 8c projectile policy (design spec
 * §2.10, §2.5). Every number is derived from the spec, not the running server —
 * the {@link Ct8cProjectilesUnit} Bukkit shell reads these constants and applies
 * these transforms to launch velocities, so pinning the math here keeps the
 * feature version-blind and testable without a live server.
 */
class Ct8cProjectilePolicyTest {

    private static final double EPS = 1e-9;

    /* ------------------------- policy constants (provenance) ------------------------- */

    @Test
    void constantsMatchTheSpec() {
        // §2.10: the thrown-projectile "rightClickDelay" the server emulates via the Cooldowns API.
        assertEquals(4, Ct8cProjectilePolicy.THROW_GATE_TICKS, "snowball/egg throw gate is 4 ticks (§2.10)");
        // §2.5 / §2.10: snowballs and eggs deal 0 damage but the full 0.4 knock (delivered by the
        // always-on projectile-knockback path under the ct8c profile — this constant documents the policy).
        assertEquals(0.4, Ct8cProjectilePolicy.FULL_KNOCK_STRENGTH, EPS, "the full 0.4 knock (§2.5/§2.10)");
        // Decompile-confirmed: 8c cut vanilla's base bow inaccuracy 1.0 → 0.25, fed as 0.25·fatigue.
        assertEquals(0.25, Ct8cProjectilePolicy.BASE_INACCURACY, EPS, "base inaccuracy 0.25 (BowItem)");
        // Decompile-confirmed: Projectile.shoot's gaussian coefficient is 0.0075F (was wrongly 0.0172275).
        assertEquals(0.0075, Ct8cProjectilePolicy.SHOOT_SPREAD_COEFF, EPS, "shoot() spread coefficient 0.0075");
        assertEquals(50_000_000L, Ct8cProjectilePolicy.NANOS_PER_TICK, "50ms per tick");
    }

    /* --------------------------------- bow fatigue --------------------------------- */

    @Test
    void fatigueIsAStepThenLinearRamp() {
        // 8c BowItem.getFatigueForTime: 0.5 below 60 ticks, a linear ramp 0.5→10.5 over 60..200,
        // clamped to 10.5 past 200. NOT the pre-2.9 step (0 spread <3s, flat 0.25 after).
        assertEquals(0.5f, Ct8cProjectilePolicy.fatigueForTime(0), "t=0 → 0.5");
        assertEquals(0.5f, Ct8cProjectilePolicy.fatigueForTime(59), "t=59 → still 0.5");
        assertEquals(0.5f, Ct8cProjectilePolicy.fatigueForTime(60), "t=60 → ramp base 0.5");
        assertEquals(5.5f, Ct8cProjectilePolicy.fatigueForTime(130), "t=130 → 0.5 + 10·70/140 = 5.5");
        assertEquals(10.5f, Ct8cProjectilePolicy.fatigueForTime(200), "t=200 → clamp 10.5");
        assertEquals(10.5f, Ct8cProjectilePolicy.fatigueForTime(400), "long hold clamps at 10.5");
    }

    @Test
    void inaccuracyIsQuarterOfFatigue() {
        // The value fed to Projectile.shoot: 0.25 · fatigue. A fresh full draw is 0.125 (never 0);
        // a fully-fatigued shot is 2.625 — ~21× the pre-2.9 flat-0.25 model's 0.25·0.0172275 spread.
        assertEquals(0.125, Ct8cProjectilePolicy.inaccuracyForTime(0), EPS, "fresh draw → 0.125");
        assertEquals(0.125, Ct8cProjectilePolicy.inaccuracyForTime(60), EPS, "at the ramp base → 0.125");
        assertEquals(2.625, Ct8cProjectilePolicy.inaccuracyForTime(200), EPS, "fully fatigued → 2.625");
    }

    @Test
    void powerFollowsTheDrawCurve() {
        // 8c BowItem.getPowerForTime: f=t/20; f=(f²+2f)/3; clamp 1.0.
        assertEquals(0.0f, Ct8cProjectilePolicy.powerForTime(0), "t=0 → 0 power");
        assertEquals(1.0f, Ct8cProjectilePolicy.powerForTime(20), "t=20 → full power (1.0)");
        assertEquals(1.0f, Ct8cProjectilePolicy.powerForTime(200), "over-draw clamps at 1.0");
    }

    @Test
    void critAllowedOnlyAtFullPowerAndUnfatigued() {
        // 8c sets the crit flag iff power==1.0 && fatigue<=0.5, i.e. a full draw within the first 60 ticks.
        assertTrue(Ct8cProjectilePolicy.critAllowed(20), "full draw, unfatigued → crit kept");
        assertTrue(Ct8cProjectilePolicy.critAllowed(60), "t=60 is the last tick fatigue is still 0.5");
        assertFalse(Ct8cProjectilePolicy.critAllowed(61), "one tick past → fatigue > 0.5 → crit stripped");
        assertFalse(Ct8cProjectilePolicy.critAllowed(10), "half draw → power < 1.0 → no crit");
    }

    /* ------------------------------ momentum projection ------------------------------ */

    @Test
    void momentumAlongAimIsKeptWhenGrounded() {
        // Shooter runs straight along the aim (grounded ⇒ vanilla inherited (0.5, 0, 0)); the projectile
        // already carries base aim (1.0, 0.2, 0) + that inheritance = (1.5, 0.2, 0). CT8c keeps only the
        // aim-direction share of the HORIZONTAL velocity (all 0.5 of it) and zeroes vertical inheritance
        // (there was none while grounded), so the vector is unchanged.
        double[] out = Ct8cProjectilePolicy.applyMomentum(1.5, 0.2, 0.0, 0.5, 0.3, 0.0, true);
        assertArrayEquals(new double[] {1.5, 0.2, 0.0}, out, EPS);
    }

    @Test
    void perpendicularMomentumIsStripped() {
        // Shooter strafes +z while aiming +x (grounded). Vanilla inherited (0, 0, 0.5) → projectile
        // (1, 0, 0.5). The strafe is perpendicular to the aim, so its aim-direction share is 0 and it is
        // fully removed: the projectile flies pure-aim (1, 0, 0).
        double[] out = Ct8cProjectilePolicy.applyMomentum(1.0, 0.0, 0.5, 0.0, 0.0, 0.5, true);
        assertArrayEquals(new double[] {1.0, 0.0, 0.0}, out, EPS);
    }

    @Test
    void diagonalPerpendicularMomentumIsStripped() {
        // Aim (0.6, 0, 0.8) (unit); shooter velocity (-0.8, 0, 0.6) is perpendicular to it (dot 0).
        // Vanilla inherited that whole velocity → projectile (-0.2, 0, 1.4). CT8c removes it entirely
        // (aim-share 0), restoring the pure diagonal aim.
        double[] out = Ct8cProjectilePolicy.applyMomentum(-0.2, 0.0, 1.4, -0.8, 0.0, 0.6, true);
        assertArrayEquals(new double[] {0.6, 0.0, 0.8}, out, EPS);
    }

    @Test
    void verticalInheritedMomentumIsZeroedWhenAirborne() {
        // Airborne shooter falling and running along +x: vanilla inherited (0.5, -0.6, 0) →
        // projectile (1.5, -0.6, 0). CT8c never inherits vertical, so the -0.6 is dropped; the
        // horizontal aim-share (0.5) is kept: (1.5, 0.0, 0).
        double[] out = Ct8cProjectilePolicy.applyMomentum(1.5, -0.6, 0.0, 0.5, -0.6, 0.0, false);
        assertArrayEquals(new double[] {1.5, 0.0, 0.0}, out, EPS);
    }

    @Test
    void straightUpShotAddsNoMomentum() {
        // Aim straight up (0, 1, 0), no horizontal aim to project onto: whatever the shooter's motion,
        // the inherited momentum is removed and none is re-added (division-by-zero guard).
        double[] out = Ct8cProjectilePolicy.applyMomentum(0.3, 1.0, 0.2, 0.3, 0.0, 0.2, true);
        assertArrayEquals(new double[] {0.0, 1.0, 0.0}, out, EPS);
    }

    /* --------------------------------- fatigue spread --------------------------------- */

    @Test
    void spreadWithZeroSampleIsCleanAimScaledToSpeed() {
        // Aim (3,0,0) normalises to (1,0,0); zero samples ⇒ the launch is exactly speed·unit = (2,0,0),
        // regardless of inaccuracy. This is why a perfectly-sampled shot flies straight down the look vector.
        double[] out = Ct8cProjectilePolicy.applySpread(3.0, 0.0, 0.0, 2.0, 2.625, 0.0, 0.0, 0.0);
        assertArrayEquals(new double[] {2.0, 0.0, 0.0}, out, EPS);
    }

    @Test
    void spreadPerturbsPerAxisWithTheShootCoefficient() {
        // perturb = 0.0075 · inaccuracy. Fresh-draw inaccuracy 0.125 ⇒ perturb 0.0009375 per unit sample.
        // Aim +x (unit), speed 2, y-sample 4 ⇒ y = (0 + 0.0009375·4)·2 = 0.0075; x stays (1)·2 = 2.
        double[] out = Ct8cProjectilePolicy.applySpread(1.0, 0.0, 0.0, 2.0, 0.125, 0.0, 4.0, 0.0);
        assertArrayEquals(new double[] {2.0, 0.0075, 0.0}, out, EPS);
    }

    @Test
    void spreadWithoutAimOrSpeedIsZero() {
        assertArrayEquals(new double[] {0.0, 0.0, 0.0},
                Ct8cProjectilePolicy.applySpread(0.0, 0.0, 0.0, 5.0, 0.125, 5.0, 5.0, 5.0), EPS);
        assertArrayEquals(new double[] {0.0, 0.0, 0.0},
                Ct8cProjectilePolicy.applySpread(1.0, 0.0, 0.0, 0.0, 0.125, 5.0, 5.0, 5.0), EPS);
    }
}
