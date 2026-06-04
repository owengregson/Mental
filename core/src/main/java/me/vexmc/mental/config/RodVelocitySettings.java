package me.vexmc.mental.config;

public record RodVelocitySettings(boolean enabled) {

    static final RodVelocitySettings DEFAULTS = new RodVelocitySettings(true);
}
