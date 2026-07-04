package me.vexmc.mental.kernel.math;

/**
 * The pocket servo's tunables (combo-hold §3.2) — a kernel-pure, immutable value
 * the {@link PocketServo} solve reads. {@code target} is the separation the servo
 * steers the victim toward at next-swing time (2.75 blocks, derived from the
 * reach-triangle in §2, not tuned); {@code gain} blends toward the full exact
 * solve (1.0 = exact); {@code min}/{@code max} are the honesty clamps ([0.8, 1.2],
 * the owner's stronger-hold decision); {@code windowTicks} is the cadence horizon
 * the flight is projected over.
 *
 * <p>{@link #INACTIVE} is the module-off / not-this-attacker value: with it the
 * solve short-circuits to {@code 1.0} and the engine multiplies by nothing
 * (byte-identical to the era stamp — zero-touch). The caller ANDs the config's
 * activeness with "this hit's attacker holds the victim's active combo," passing
 * {@link #INACTIVE} otherwise, so a single {@code active()} gate covers both.</p>
 */
public record PocketServoConfig(
        boolean active, double target, double gain, double min, double max, int windowTicks) {

    /** The module-off / not-applicable value — the solve returns exactly 1.0. */
    public static final PocketServoConfig INACTIVE = new PocketServoConfig(false, 2.75, 1.0, 0.8, 1.2, 10);

    /** An active config with the given knobs (the config parser's constructor). */
    public static PocketServoConfig of(double target, double gain, double min, double max, int windowTicks) {
        return new PocketServoConfig(true, target, gain, min, max, windowTicks);
    }
}
