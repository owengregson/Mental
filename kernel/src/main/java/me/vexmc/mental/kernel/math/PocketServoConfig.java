package me.vexmc.mental.kernel.math;

/**
 * The pocket servo's tunables (combo-hold §3.2/§3.2b; the 2.4.5 answer-denial
 * redesign) — a kernel-pure, immutable value the {@link PocketServo} solve reads.
 *
 * <p>{@code targetMode} selects how the steered separation is chosen:
 * {@link TargetMode#BOUNDARY} (the default) computes the geometric answer-denial
 * target from the reach geometry; {@link TargetMode#STATIC} pins it to {@code
 * staticTarget}. {@code staticTarget} is also the BOUNDARY mode's degrade fallback
 * when the geometry is unmeasurable (no facing).</p>
 *
 * <p><b>The reach geometry (one unified basis — feet-to-feet separation,
 * eye→AABB reach).</b> {@code victimReach} is the victim's EFFECTIVE answer reach
 * ({@code R_v}); the caller folds in the combo reach handicap (era 3.0 → 2.61 at
 * scale 0.87) before building the config, so the solve sees the reach the victim
 * actually has while their combo is held. {@code attackerReach} ({@code R_a}, era
 * 3.0) is never handicapped. {@code denyMargin} is the hair past the victim's deny
 * boundary the servo aims for (so jitter can't let them answer); {@code
 * jitterMargin} is the slack below the attacker's reach the target keeps (so the
 * attacker reliably connects); {@code targetFloor} is the closest the servo will
 * ever pull the victim IN.</p>
 *
 * <p>{@code gain} blends toward the full exact solve (1.0 = exact); {@code
 * min}/{@code max} are the honesty clamps ([0.8, 1.2], the owner's stronger-hold
 * decision); {@code windowTicks} is the base cadence horizon (10) the flight is
 * projected over.</p>
 *
 * <p>{@link #INACTIVE} is the module-off / not-this-attacker value: with it the
 * solve short-circuits to {@code 1.0} and the engine multiplies by nothing
 * (byte-identical to the era stamp — zero-touch). The caller ANDs the config's
 * activeness with "this hit's attacker holds the victim's active combo," passing
 * {@link #INACTIVE} otherwise, so a single {@code active()} gate covers both.</p>
 */
public record PocketServoConfig(
        boolean active, double staticTarget, double gain, double min, double max,
        int windowTicks, TargetMode targetMode,
        double victimReach, double attackerReach,
        double denyMargin, double jitterMargin, double targetFloor) {

    /** The era melee reach in blocks — the eye→AABB reach both the deny and reach boundaries use. */
    public static final double DEFAULT_REACH = 3.0;

    /** The default STATIC fallback separation (the lab's held-separation equilibrium). */
    public static final double DEFAULT_STATIC_TARGET = 2.85;

    /** The default hair past the deny boundary the servo aims for. */
    public static final double DEFAULT_DENY_MARGIN = 0.02;

    /** The default slack below the attacker's reach the target keeps so it reliably connects. */
    public static final double DEFAULT_JITTER_MARGIN = 0.15;

    /** The default floor — the closest the servo will ever pull the victim in. */
    public static final double DEFAULT_TARGET_FLOOR = 2.5;

    /** The module-off / not-applicable value — the solve returns exactly 1.0 (the target is never read). */
    public static final PocketServoConfig INACTIVE =
            new PocketServoConfig(false, DEFAULT_STATIC_TARGET, 1.0, 0.8, 1.2, 10,
                    TargetMode.BOUNDARY, DEFAULT_REACH, DEFAULT_REACH,
                    DEFAULT_DENY_MARGIN, DEFAULT_JITTER_MARGIN, DEFAULT_TARGET_FLOOR);

    /**
     * An active BOUNDARY-mode config with the given knobs and the default reach
     * geometry (the v1 / pre-precision-round arity — every existing caller and pin
     * uses this). {@code staticTarget} is the STATIC fallback; the v1 four-input
     * solve, which has no facing geometry, always steers to it.
     */
    public static PocketServoConfig of(double staticTarget, double gain, double min, double max, int windowTicks) {
        return of(staticTarget, gain, min, max, windowTicks, TargetMode.BOUNDARY,
                DEFAULT_REACH, DEFAULT_REACH, DEFAULT_DENY_MARGIN, DEFAULT_JITTER_MARGIN, DEFAULT_TARGET_FLOOR);
    }

    /** The full active config carrying the target mode and the reach geometry (the config parser's constructor). */
    public static PocketServoConfig of(
            double staticTarget, double gain, double min, double max, int windowTicks,
            TargetMode targetMode, double victimReach, double attackerReach,
            double denyMargin, double jitterMargin, double targetFloor) {
        return new PocketServoConfig(true, staticTarget, gain, min, max, windowTicks,
                targetMode, victimReach, attackerReach, denyMargin, jitterMargin, targetFloor);
    }
}
