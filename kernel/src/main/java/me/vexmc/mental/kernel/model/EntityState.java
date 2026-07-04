package me.vexmc.mental.kernel.model;

/**
 * The exact slice of an entity the knockback formula consumes — capturable
 * on the owning thread, then usable from anywhere.
 *
 * <p>{@code moveSpeedAttr} is the attacker's effective movement-speed attribute,
 * consumed only by speed-conformal knockback (pace scaling). It is meaningful
 * for an ATTACKER capture; victim captures, projectile sources, and every
 * pre-pace-scaling construction leave it {@link #MOVE_SPEED_UNAVAILABLE} (the
 * 11-arg constructor), which resolves to the stance baseline (pace factor 1.0).
 * Additive growth: the 11-arg constructor preserves every existing construction
 * byte-for-byte.</p>
 */
public record EntityState(
        double x,
        double y,
        double z,
        float yaw,
        double vx,
        double vy,
        double vz,
        boolean grounded,
        boolean sprinting,
        int knockbackEnchantLevel,
        double knockbackResistance,
        double moveSpeedAttr) {

    /**
     * The attacker's movement-speed attribute could not be read (below the
     * attribute API, or a mob source). Pace scaling resolves it to the stance
     * baseline ⇒ factor 1.0 — never silently something else. Any non-positive
     * value means the same; movement speed is always strictly positive.
     */
    public static final double MOVE_SPEED_UNAVAILABLE = -1.0;

    /**
     * The pre-pace-scaling construction: movement-speed attribute unavailable.
     * Every capture that is not a pace-scaling attacker (victims, mobs,
     * projectile sources) and every existing call site uses this arity.
     */
    public EntityState(
            double x,
            double y,
            double z,
            float yaw,
            double vx,
            double vy,
            double vz,
            boolean grounded,
            boolean sprinting,
            int knockbackEnchantLevel,
            double knockbackResistance) {
        this(x, y, z, yaw, vx, vy, vz, grounded, sprinting,
                knockbackEnchantLevel, knockbackResistance, MOVE_SPEED_UNAVAILABLE);
    }
}
