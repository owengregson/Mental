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
        // §2.10: base/crossbow inaccuracy is 0.25 — a fatigued shot's spread.
        assertEquals(0.25, Ct8cProjectilePolicy.SPREAD_INACCURACY, EPS, "fatigued-shot inaccuracy is 0.25 (§2.10)");
        // §2.10: accuracy decay only after 3 seconds held.
        assertEquals(3_000_000_000L, Ct8cProjectilePolicy.FATIGUE_NANOS, "bow fatigue after 3s held (§2.10)");
    }

    /* --------------------------------- bow fatigue --------------------------------- */

    @Test
    void fatigueTriggersStrictlyAfterThreeSeconds() {
        assertFalse(Ct8cProjectilePolicy.fatigued(0L), "an instant release is never fatigued");
        assertFalse(Ct8cProjectilePolicy.fatigued(2_999_999_999L), "just under 3s is not yet fatigued");
        assertFalse(Ct8cProjectilePolicy.fatigued(3_000_000_000L), "exactly 3s is the boundary, not past it");
        assertTrue(Ct8cProjectilePolicy.fatigued(3_000_000_001L), "just past 3s is fatigued");
        assertTrue(Ct8cProjectilePolicy.fatigued(10_000_000_000L), "a long hold is fatigued");
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
    void spreadWithZeroSampleIsIdentity() {
        double[] out = Ct8cProjectilePolicy.applySpread(2.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        assertArrayEquals(new double[] {2.0, 0.0, 0.0}, out, EPS);
    }

    @Test
    void spreadPerturbsPerAxisScaledBySpeed() {
        // K = 0.0172275 · 0.25 = 0.004306875 per unit sample. Speed 2, unit dir +x, a y-sample of 4:
        // y-component = (0 + K·4) · 2 = 0.0172275 · 2 = 0.034455; x stays (1)·2 = 2.
        double[] out = Ct8cProjectilePolicy.applySpread(2.0, 0.0, 0.0, 0.0, 4.0, 0.0);
        assertArrayEquals(new double[] {2.0, 0.034455, 0.0}, out, EPS);
    }

    @Test
    void spreadOfZeroVelocityIsZero() {
        double[] out = Ct8cProjectilePolicy.applySpread(0.0, 0.0, 0.0, 5.0, 5.0, 5.0);
        assertArrayEquals(new double[] {0.0, 0.0, 0.0}, out, EPS);
    }
}
