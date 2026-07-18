package me.vexmc.mental.v5.config.settings;

/**
 * The projectile-knockback feature's tunables, ported field-for-field (minus
 * the module toggle) from the retired {@code config.ProjectileKnockbackSettings}.
 * The YAML surface is frozen; defaults are byte-identical.
 */
public record ProjectileKnockbackSettings(
        boolean arrows,
        double snowballDamage,
        double eggDamage,
        double enderPearlDamage) {

    public static final ProjectileKnockbackSettings DEFAULTS =
            new ProjectileKnockbackSettings(true, 0.0001, 0.0001, 0.0001);
}
