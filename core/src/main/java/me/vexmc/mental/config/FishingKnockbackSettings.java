package me.vexmc.mental.config;

import org.jetbrains.annotations.NotNull;

public record FishingKnockbackSettings(
        boolean enabled,
        double damage,
        @NotNull ReelInPolicy reelIn,
        boolean knockbackNonPlayerEntities) {

    static final FishingKnockbackSettings DEFAULTS =
            new FishingKnockbackSettings(true, 0.0001, ReelInPolicy.LEGACY, false);

    static @NotNull FishingKnockbackSettings parse(@NotNull ConfigReader reader) {
        return new FishingKnockbackSettings(
                reader.flag("enabled", DEFAULTS.enabled),
                reader.numberAtLeast("damage", DEFAULTS.damage, 0),
                reader.oneOf("reel-in", DEFAULTS.reelIn, ReelInPolicy.class),
                reader.flag("knockback-non-player-entities", DEFAULTS.knockbackNonPlayerEntities));
    }
}
