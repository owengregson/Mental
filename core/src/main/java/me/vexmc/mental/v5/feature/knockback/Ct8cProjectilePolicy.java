package me.vexmc.mental.v5.feature.knockback;

/**
 * The pure Combat Test 8c projectile policy (design spec §2.10, §2.5) — the
 * version-blind numbers and launch-velocity transforms the {@link
 * Ct8cProjectilesUnit} Bukkit shell applies. Kept Bukkit-free so every value has
 * a hand-computed unit pin (the kernel-style discipline: math in its own class,
 * shells stay thin).
 *
 * <p>Provenance: every constant is code-confirmed against the SHA1-verified
 * {@code 1_16_combat-6} decompile summarised in the design spec (§2.10 ranged
 * rules, §2.5 the 0.4 knock). The 0.4 knock itself is <em>not</em> shipped from
 * here: snowballs and eggs already knock through the always-on {@code
 * projectile-knockback} desk path, and under the {@code ct8c} knockback profile
 * (base 0.4, vertical-cap 0.4) that path delivers exactly the CT8c 0.4 — this
 * class only documents the policy constant and owns the <em>other</em> CT8c
 * projectile rules (the throw gate, the aim-direction momentum rewrite, and the
 * bow-fatigue spread).</p>
 */
final class Ct8cProjectilePolicy {

    /**
     * The snowball/egg throw gate, in ticks — CT8c's client {@code rightClickDelay}
     * that the server emulates through the item-cooldown API (§2.10). Four ticks
     * between thrown-projectile releases.
     */
    static final int THROW_GATE_TICKS = 4;

    /**
     * The full CT8c knock strength snowballs and eggs deliver despite dealing 0
     * damage (§2.5/§2.10). Delivered by the always-on projectile-knockback path
     * under the ct8c profile, not shipped from this unit — this constant is the
     * policy's provenance pin.
     */
    static final double FULL_KNOCK_STRENGTH = 0.4;

    /**
     * The inaccuracy a fatigued bow shot suffers (§2.10 — "Base/crossbow
     * inaccuracy 0.25"). A non-fatigued player shot is perfectly accurate; past
     * the fatigue threshold the shot spreads by this factor.
     */
    static final double SPREAD_INACCURACY = 0.25;

    /**
     * The bow-fatigue threshold in nanoseconds (§2.10 — "accuracy decay only
     * after 3 seconds held"). A draw held strictly longer than this cannot be a
     * critical arrow and takes the {@link #SPREAD_INACCURACY} spread.
     */
    static final long FATIGUE_NANOS = 3_000_000_000L;

    /**
     * Vanilla's per-inaccuracy-unit spread coefficient (the {@code
     * 0.0172275·inaccuracy} term of {@code Projectile.shoot}). Multiplied by
     * {@link #SPREAD_INACCURACY} it gives the per-sample perturbation a fatigued
     * shot's direction receives before rescaling to the original speed.
     */
    private static final double INACCURACY_UNIT = 0.0172275;

    /** A horizontal aim below this length is treated as a straight up/down shot (no momentum to project). */
    private static final double AIM_EPSILON = 1.0e-8;

    private Ct8cProjectilePolicy() {}

    /** Whether a bow draw held for {@code drawNanos} nanoseconds is fatigued (strictly past 3 seconds). */
    static boolean fatigued(long drawNanos) {
        return drawNanos > FATIGUE_NANOS;
    }

    /**
     * Rewrites a thrown projectile's launch velocity to CT8c's momentum rule
     * (§2.10): player momentum is inherited <em>only in the aim direction</em>,
     * and vertical inherited momentum is never carried.
     *
     * <p>Vanilla adds the shooter's motion {@code (vx, onGround ? 0 : vy, vz)} to
     * the projectile at spawn, before the launch event fires. {@code getVelocity}
     * returns the same server-side delta vanilla used, so subtracting that
     * inheritance recovers the pure aim vector; CT8c then re-adds only the scalar
     * projection of the shooter's <em>horizontal</em> velocity onto the horizontal
     * aim (the "aim-direction share"), leaving the vertical exactly the aim's. A
     * straight up/down shot (no horizontal aim) receives no momentum.</p>
     *
     * @return {@code {x, y, z}} — the rewritten launch velocity.
     */
    static double[] applyMomentum(
            double projVx, double projVy, double projVz,
            double shooterVx, double shooterVy, double shooterVz, boolean shooterGrounded) {
        double inheritedX = shooterVx;
        double inheritedY = shooterGrounded ? 0.0 : shooterVy;
        double inheritedZ = shooterVz;
        double baseX = projVx - inheritedX;
        double baseY = projVy - inheritedY;
        double baseZ = projVz - inheritedZ;
        double horizontal = Math.hypot(baseX, baseZ);
        if (horizontal < AIM_EPSILON) {
            return new double[] {baseX, baseY, baseZ};
        }
        double unitX = baseX / horizontal;
        double unitZ = baseZ / horizontal;
        double share = shooterVx * unitX + shooterVz * unitZ;
        return new double[] {baseX + share * unitX, baseY, baseZ + share * unitZ};
    }

    /**
     * Perturbs a launch velocity by CT8c's fatigued-shot inaccuracy (§2.10),
     * mirroring vanilla's {@code Projectile.shoot} spread: normalise, add
     * {@code INACCURACY_UNIT · SPREAD_INACCURACY · sample} per axis, then rescale
     * to the original speed. The random samples are passed in so the transform is
     * deterministic and unit-pinnable (the shell feeds {@code Random#nextGaussian}).
     *
     * @return {@code {x, y, z}} — the perturbed launch velocity (zero if the input was zero).
     */
    static double[] applySpread(double vx, double vy, double vz, double sampleX, double sampleY, double sampleZ) {
        double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (speed < AIM_EPSILON) {
            return new double[] {0.0, 0.0, 0.0};
        }
        double perturb = INACCURACY_UNIT * SPREAD_INACCURACY;
        double unitX = vx / speed;
        double unitY = vy / speed;
        double unitZ = vz / speed;
        return new double[] {
            (unitX + perturb * sampleX) * speed,
            (unitY + perturb * sampleY) * speed,
            (unitZ + perturb * sampleZ) * speed
        };
    }
}
