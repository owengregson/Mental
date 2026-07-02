package me.vexmc.mental.kernel.model;

/**
 * The exact slice of an entity the knockback formula consumes — capturable
 * on the owning thread, then usable from anywhere.
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
        double knockbackResistance) {
}
