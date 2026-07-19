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
     * CT8c's base bow-inaccuracy factor — the {@code 0.25} the snapshot cut
     * vanilla's ranged spread down to (vanilla bows shoot at inaccuracy 1.0).
     * The value fed to {@code Projectile.shoot} is {@code BASE_INACCURACY ·
     * fatigue}, so even a fresh, unfatigued draw carries a small {@code 0.125}
     * spread — never the perfectly-straight shot vanilla plugins assume.
     *
     * <p>Decompile-confirmed against {@code BowItem.releaseUsing}
     * ({@code arrow.shootFromRotation(…, power·3, 0.25F · getFatigueForTime(t))}).</p>
     */
    static final double BASE_INACCURACY = 0.25;

    /**
     * Vanilla {@code Projectile.shoot}'s per-inaccuracy gaussian coefficient —
     * {@code 0.0075F}, read directly from the {@code 1_16_combat-6} decompile
     * ({@code .add(random.nextGaussian() * 0.0075F * inaccuracy, …)}). This is
     * the true coefficient the launch spread multiplies the sampled gaussian by;
     * the pre-2.9 {@code 0.0172275} constant was wrong.
     */
    static final double SHOOT_SPREAD_COEFF = 0.0075;

    /** Nanoseconds per server tick — the bow draw is a tick count in 8c ({@code useDuration − remaining}). */
    static final long NANOS_PER_TICK = 50_000_000L;

    /**
     * The draw length assumed when the right-click stamp is missing (a shot that
     * reached {@code EntityShootBowEvent} without our interact stamp): a full,
     * unfatigued draw — {@code fatigue 0.5}, {@code power 1.0} — so we never
     * strip a legitimate crit nor over-spread a real aimed shot.
     */
    static final int UNKNOWN_DRAW_TICKS = 20;

    /** A vector below this length is treated as degenerate (no aim / no speed). */
    private static final double AIM_EPSILON = 1.0e-8;

    private Ct8cProjectilePolicy() {}

    /** The whole-tick draw length a {@code drawNanos}-nanosecond hold represents (8c measures the draw in ticks). */
    static int ticksHeld(long drawNanos) {
        return (int) (drawNanos / NANOS_PER_TICK);
    }

    /**
     * CT8c {@code BowItem.getPowerForTime}: {@code f = t/20}; {@code f =
     * (f² + 2f)/3}; clamp to {@code 1.0}. The draw power that scales the launch
     * speed ({@code power · 3}) and gates the critical arrow.
     */
    static float powerForTime(int ticks) {
        float f = ticks / 20.0f;
        f = (f * f + f * 2.0f) / 3.0f;
        return f > 1.0f ? 1.0f : f;
    }

    /**
     * CT8c {@code BowItem.getFatigueForTime}: {@code t < 60 → 0.5}; {@code t ≥
     * 200 → 10.5}; else a linear ramp {@code 0.5 + 10·(t−60)/140}. This is the
     * multiplier on {@link #BASE_INACCURACY} — a ramp, not the pre-2.9 step
     * function (which shipped zero spread below 3s and a flat 0.25 above it).
     */
    static float fatigueForTime(int ticks) {
        if (ticks < 60) {
            return 0.5f;
        }
        return ticks >= 200 ? 10.5f : 0.5f + 10.0f * (ticks - 60) / 140.0f;
    }

    /** The inaccuracy CT8c feeds {@code Projectile.shoot}: {@link #BASE_INACCURACY} · {@link #fatigueForTime}. */
    static double inaccuracyForTime(int ticks) {
        return BASE_INACCURACY * fatigueForTime(ticks);
    }

    /**
     * Whether the arrow stays critical: CT8c sets the crit flag only for a
     * full-power, unfatigued draw ({@code power == 1.0F && fatigue <= 0.5F},
     * i.e. drawn to full within the first 60 ticks). Past 60 ticks the ramp
     * lifts fatigue above 0.5 and the shot can no longer crit.
     */
    static boolean critAllowed(int ticks) {
        return powerForTime(ticks) >= 1.0f && fatigueForTime(ticks) <= 0.5f;
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
     * CT8c {@code Projectile.shoot}'s launch spread from a <em>clean</em> aim:
     * normalise the aim, add {@link #SHOOT_SPREAD_COEFF} {@code · inaccuracy ·
     * sample} per axis, then scale by the launch speed — exactly
     * {@code new Vec3(aim).normalize().add(0.0075·inaccuracy·gaussian, …).scale(speed)}.
     *
     * <p>The aim is the shooter's clean look direction (not the already-spread
     * projectile velocity), so this <em>replaces</em> vanilla's inaccuracy-1.0
     * spread with 8c's {@code 0.25 · fatigue} rather than compounding on top of
     * it. The samples are passed in so the transform is deterministic and
     * unit-pinnable (the shell feeds {@code Random#nextGaussian}). {@code speed}
     * is the projectile's launch magnitude (vanilla {@code power · 3}).</p>
     *
     * @return {@code {x, y, z}} — the spread launch velocity (zero if aim or speed is degenerate).
     */
    static double[] applySpread(
            double aimX, double aimY, double aimZ, double speed, double inaccuracy,
            double sampleX, double sampleY, double sampleZ) {
        double len = Math.sqrt(aimX * aimX + aimY * aimY + aimZ * aimZ);
        if (len < AIM_EPSILON || speed < AIM_EPSILON) {
            return new double[] {0.0, 0.0, 0.0};
        }
        double unitX = aimX / len;
        double unitY = aimY / len;
        double unitZ = aimZ / len;
        double perturb = SHOOT_SPREAD_COEFF * inaccuracy;
        return new double[] {
            (unitX + perturb * sampleX) * speed,
            (unitY + perturb * sampleY) * speed,
            (unitZ + perturb * sampleZ) * speed
        };
    }
}
