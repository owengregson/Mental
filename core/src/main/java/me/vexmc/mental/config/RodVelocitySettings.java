package me.vexmc.mental.config;

import org.jetbrains.annotations.NotNull;

public record RodVelocitySettings(boolean enabled) {

    static final RodVelocitySettings DEFAULTS = new RodVelocitySettings(true);

    static @NotNull RodVelocitySettings parse(@NotNull ConfigReader reader) {
        return new RodVelocitySettings(reader.flag("enabled", DEFAULTS.enabled));
    }
}
