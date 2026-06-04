package me.vexmc.mental.config;

import org.jetbrains.annotations.NotNull;

public record CompatibilitySettings(@NotNull OcmCoordination oldCombatMechanics) {

    static final CompatibilitySettings DEFAULTS = new CompatibilitySettings(OcmCoordination.AUTO);

    static @NotNull CompatibilitySettings parse(@NotNull ConfigReader reader) {
        return new CompatibilitySettings(
                reader.oneOf("old-combat-mechanics", DEFAULTS.oldCombatMechanics, OcmCoordination.class));
    }
}
