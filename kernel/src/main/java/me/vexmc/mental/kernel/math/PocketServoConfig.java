package me.vexmc.mental.kernel.math;

/**
 * The pocket servo's tunables (combo-hold §3.2/§3.2b) — a kernel-pure, immutable
 * value the {@link PocketServo} solve reads. {@code target} is the anchor
 * separation the servo steers the victim toward at next-swing time (2.75 blocks,
 * the hittability centre derived from the reach-triangle in §2, not tuned);
 * {@code gain} blends toward the full exact solve (1.0 = exact); {@code min}/{@code
 * max} are the honesty clamps ([0.8, 1.2], the owner's stronger-hold decision);
 * {@code windowTicks} is the cadence horizon (10) the flight is projected over.
 *
 * <p><b>Precision-round growth (§3.2b).</b> {@code targetMode} selects the anchor
 * (the default — the computed dynamic value is journaled to the debug sink but not
 * used, so the lab round can calibrate the geometry first) or the exposure-budget
 * dynamic target (precision-derivation §3.3), which relaxes between the anchor and
 * {@code hitCap} (2.95 — the practical hittable edge). Both defaults preserve the
 * v1 feel: {@link #of(double, double, double, double, int)} builds an anchor-mode
 * config with the 2.95 hit cap, so every existing caller and pin is unchanged.</p>
 *
 * <p>{@link #INACTIVE} is the module-off / not-this-attacker value: with it the
 * solve short-circuits to {@code 1.0} and the engine multiplies by nothing
 * (byte-identical to the era stamp — zero-touch). The caller ANDs the config's
 * activeness with "this hit's attacker holds the victim's active combo," passing
 * {@link #INACTIVE} otherwise, so a single {@code active()} gate covers both.</p>
 */
public record PocketServoConfig(
        boolean active, double target, double gain, double min, double max,
        int windowTicks, TargetMode targetMode, double hitCap) {

    /** The practical hittable edge — the dynamic target's upper clamp (§3.1, hitCap ≈ 2.95). */
    public static final double DEFAULT_HIT_CAP = 2.95;

    /** The module-off / not-applicable value — the solve returns exactly 1.0. */
    public static final PocketServoConfig INACTIVE =
            new PocketServoConfig(false, 2.75, 1.0, 0.8, 1.2, 10, TargetMode.ANCHOR, DEFAULT_HIT_CAP);

    /**
     * An active anchor-mode config with the given knobs and the default 2.95 hit
     * cap (the v1 / pre-precision-round arity — every existing caller and pin uses
     * this, so anchor mode is the zero-config default).
     */
    public static PocketServoConfig of(double target, double gain, double min, double max, int windowTicks) {
        return of(target, gain, min, max, windowTicks, TargetMode.ANCHOR, DEFAULT_HIT_CAP);
    }

    /** An active config carrying the precision-round target mode and hit cap (the config parser's constructor). */
    public static PocketServoConfig of(
            double target, double gain, double min, double max, int windowTicks,
            TargetMode targetMode, double hitCap) {
        return new PocketServoConfig(true, target, gain, min, max, windowTicks, targetMode, hitCap);
    }
}
