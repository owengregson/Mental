package me.vexmc.mental.kernel.math;

import me.vexmc.mental.kernel.model.KnockbackVector;

/**
 * The legacy Punch enchantment addition, extracted from the projectile
 * knockback module: arrows add Punch exactly as 1.7.10 did — {@code 0.6/level}
 * along the arrow's horizontal flight plus {@code 0.1} vertical, additive
 * after the base knock and never resistance-scaled.
 */
public final class PunchMath {

    private static final double PUNCH_HORIZONTAL_PER_LEVEL = 0.6;
    private static final double PUNCH_VERTICAL = 0.1;

    private PunchMath() {}

    /**
     * Adds the Punch bonus for {@code level} onto {@code base}, directed along
     * the arrow's horizontal flight {@code (flightX, flightZ)}. Level zero (or
     * a degenerate vertical flight) returns the base unchanged; the result
     * re-clamps to the legacy packet limit.
     */
    public static KnockbackVector withPunch(KnockbackVector base, double flightX, double flightZ, int level) {
        if (level <= 0) {
            return base;
        }
        double horizontal = Math.hypot(flightX, flightZ);
        if (horizontal <= 1.0e-4) {
            return base;
        }
        return KnockbackEngine.clamp(
                base.x() + flightX / horizontal * PUNCH_HORIZONTAL_PER_LEVEL * level,
                base.y() + PUNCH_VERTICAL,
                base.z() + flightZ / horizontal * PUNCH_HORIZONTAL_PER_LEVEL * level);
    }
}
