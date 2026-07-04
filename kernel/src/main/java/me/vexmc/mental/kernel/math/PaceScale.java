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
 *   s = clamp(min, max, (attr / baseline(stance))^exponent)
 * </pre>
 *
 * <p>{@code baseline(stance)} is the movement-speed attribute a base-speed
 * attacker reads in that stance — {@link #SPRINT_BASELINE} when sprinting,
 * {@link #WALK_BASELINE} otherwise (assumption A2, measured: the effective
 * attribute already carries the sprint modifier ×1.3 and any Speed/Slowness
 * modifier). Plain base-speed play therefore yields {@code attr / baseline == 1}
 * exactly and {@code s == 1.0}, so the era stamp ships byte-identically.</p>
 *
 * <p>Worked pins (exponent 1): base sprint {@code 0.13/0.13 = 1.0}; base walk
 * {@code 0.10/0.10 = 1.0}; Speed III sprint {@code 0.208/0.13 = 1.6}; Speed III
 * walk {@code 0.16/0.10 = 1.6}; Slowness II sprint {@code 0.091/0.13 = 0.7}.</p>
 */
public final class PaceScale {

    /** Base-speed sprinting movement-speed attribute: 0.1 base × the 1.3 sprint modifier. */
    public static final double SPRINT_BASELINE = 0.13;

    /** Base-speed walking movement-speed attribute: the player base of 0.1. */
    public static final double WALK_BASELINE = 0.10;

    private PaceScale() {}

    /**
     * The pace factor for a hit. Mode {@code OFF} returns exactly {@code 1.0}
     * (the engine then skips the multiply entirely — byte-identity, not merely
     * numerical). An unavailable attribute — {@link
     * me.vexmc.mental.kernel.model.EntityState#MOVE_SPEED_UNAVAILABLE}, or any
     * non-positive value — resolves to the stance baseline, i.e. {@code s = 1.0},
     * never silently something else.
     *
     * @param attackerMoveSpeedAttr the attacker's effective movement-speed attribute
     * @param sprinting the attack-time stance (selects the baseline; matches the
     *     sprint flag the engine's bonus uses)
     * @param config the profile's pace-scaling knob
     */
    public static double factor(double attackerMoveSpeedAttr, boolean sprinting, PaceScaling config) {
        if (!config.active()) {
            return 1.0;
        }
        double baseline = sprinting ? SPRINT_BASELINE : WALK_BASELINE;
        // Unavailable (sentinel / impossible non-positive) ⇒ the baseline ⇒ s = 1.0.
        double attr = attackerMoveSpeedAttr > 0.0 ? attackerMoveSpeedAttr : baseline;
        double raw = Math.pow(attr / baseline, config.exponent());
        return Math.max(config.min(), Math.min(config.max(), raw));
    }
}
