package me.vexmc.mental.config;

import org.jetbrains.annotations.NotNull;

public record KnockbackSettings(boolean enabled, @NotNull KnockbackProfile profile) {

    static final KnockbackSettings DEFAULTS =
            new KnockbackSettings(true, KnockbackProfile.LEGACY_17);

    static @NotNull KnockbackSettings parse(@NotNull ConfigReader reader) {
        return new KnockbackSettings(
                reader.flag("enabled", DEFAULTS.enabled),
                KnockbackProfile.parse(
                        KnockbackProfile.LEGACY_17.name(),
                        KnockbackProfile.LEGACY_17.displayName(),
                        KnockbackProfile.LEGACY_17.description(),
                        reader));
    }
}
