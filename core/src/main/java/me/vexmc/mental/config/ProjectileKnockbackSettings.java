package me.vexmc.mental.config;

import org.jetbrains.annotations.NotNull;

public record ProjectileKnockbackSettings(
        boolean enabled,
        boolean arrows,
        double snowballDamage,
        double eggDamage,
        double enderPearlDamage) {

    static final ProjectileKnockbackSettings DEFAULTS =
            new ProjectileKnockbackSettings(true, true, 0.0001, 0.0001, 0.0001);

    static @NotNull ProjectileKnockbackSettings parse(boolean enabled, @NotNull ConfigReader reader) {
        ConfigReader damage = reader.sub("damage");
        return new ProjectileKnockbackSettings(
                enabled,
                reader.flag("arrows", DEFAULTS.arrows),
                damage.numberAtLeast("snowball", DEFAULTS.snowballDamage, 0),
                damage.numberAtLeast("egg", DEFAULTS.eggDamage, 0),
                damage.numberAtLeast("ender-pearl", DEFAULTS.enderPearlDamage, 0));
    }
}
