package me.vexmc.mental.kernel.math;

import me.vexmc.mental.kernel.profile.PaceScaling;

/**
 * The pace factor {@code s}: how much to scale the horizontal knock Mental
 * delivers, given the attacker's movement-speed attribute and stance
 * (speed-conformal knockback, design 2026-07-04). Pure math, no Bukkit; the
 * {@link me.vexmc.mental.kernel.math.KnockbackEngine} applies the result to the
 * fresh knock (base push + sprint/wtap/enchant extras), horizontal only.
 *
 * <pre>
 *   s = clamp(min, max, (normalizedAttr / WALK_BASELINE)^exponent)
 * </pre>
 *
 * <p>{@code normalizedAttr} is the attacker's WALK-STANCE-NORMALIZED
 * movement-speed attribute — the effective value with the ×1.3 sprint modifier
 * already divided back out at capture (assumption A2, javap-verified; see
 * {@code Attributes.movementSpeedWalkNormalized}). A single {@link #WALK_BASELINE}
 * of 0.10 therefore serves BOTH stances, and the pace factor is immune to
 * wire-vs-server stance disagreement (the 2.4.0 desync, F1). Plain base-speed
 * play yields {@code normalizedAttr / WALK_BASELINE ≈ 1} and {@code s ≈ 1.0}: the
 * live base attribute is the float {@code 0.1f == 0.10000000149011612}, so the
 * ratio carries ~1.5e-8 of float slack (below the wire quantum {@code 1.25e-4},
 * so packets are byte-identical) — the attr-unavailable sentinel path is the only
 * one that returns EXACTLY 1.0.</p>
 *
 * <p>Worked pins (exponent 1): base (either stance) {@code 0.10/0.10 = 1.0};
 * Speed III {@code 0.16/0.10 = 1.6}; Slowness II {@code 0.07/0.10 = 0.7}. The
 * sprint modifier cancels at capture, so the sprint and walk rows collapse to one.</p>
 */
public final class PaceScale {

    /** Base-speed sprinting movement-speed attribute (0.1 base × the 1.3 sprint modifier) — the pre-normalization baseline the deprecated 3-arg {@link #factor(double, boolean, PaceScaling)} still selects. */
    public static final double SPRINT_BASELINE = 0.13;

    /** Base-speed movement-speed attribute: the player base of 0.1 — the single walk-normalized baseline. */
    public static final double WALK_BASELINE = 0.10;

    private PaceScale() {}

    /**
     * The pace factor for a hit, over a WALK-STANCE-NORMALIZED attribute. Mode
     * {@code OFF} returns exactly {@code 1.0} (the engine then skips the multiply
     * entirely — byte-identity, not merely numerical). An unavailable attribute —
     * {@link me.vexmc.mental.kernel.model.EntityState#MOVE_SPEED_UNAVAILABLE}, or
     * any non-positive value — resolves to the walk baseline, i.e. {@code s = 1.0}
     * exactly, never silently something else.
     *
     * @param normalizedAttr the attacker's walk-stance-normalized movement-speed
     *     attribute (the sprint modifier stripped at capture)
     * @param config the profile's pace-scaling knob
     */
    public static double factor(double normalizedAttr, PaceScaling config) {
        if (!config.active()) {
            return 1.0;
        }
        // Unavailable (sentinel / impossible non-positive) ⇒ the walk baseline ⇒ s = 1.0.
        double attr = normalizedAttr > 0.0 ? normalizedAttr : WALK_BASELINE;
        double raw = Math.pow(attr / WALK_BASELINE, config.exponent());
        return Math.max(config.min(), Math.min(config.max(), raw));
    }

    /**
     * The legacy stance-baseline factor: divides by {@link #SPRINT_BASELINE} when
     * {@code sprinting}, {@link #WALK_BASELINE} otherwise.
     *
     * @deprecated Superseded by {@link #factor(double, PaceScaling)} (2.4.1). This
     *     form permitted the F1 stance desync — it pairs a sprint boolean (often
     *     wire-fresh) with an attribute captured a different instant, so a wire
     *     START that had not yet reached the server (or a same-flush STOP that
     *     had) divided {@code 0.10/0.13 = 0.769} (a ~23% weak knock inside the
     *     [0.5, 2.0] clamp). The walk-normalized 2-arg form strips the sprint
     *     modifier at capture, so no stance boolean feeds the baseline at all.
     *     Retained only for the pre-normalization unit pins.
     */
    @Deprecated
    public static double factor(double attackerMoveSpeedAttr, boolean sprinting, PaceScaling config) {
        if (!config.active()) {
            return 1.0;
        }
        double baseline = sprinting ? SPRINT_BASELINE : WALK_BASELINE;
        double attr = attackerMoveSpeedAttr > 0.0 ? attackerMoveSpeedAttr : baseline;
        double raw = Math.pow(attr / baseline, config.exponent());
        return Math.max(config.min(), Math.min(config.max(), raw));
    }
}
