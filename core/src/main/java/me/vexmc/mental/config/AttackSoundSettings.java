package me.vexmc.mental.config;

/**
 * The disable-attack-sounds module switch (config.yml, {@code modules} map —
 * no tunables of its own). When enabled, Mental intercepts outbound
 * {@code SOUND_EFFECT} and {@code ENTITY_SOUND_EFFECT} packets and cancels any
 * sound in the {@code entity.player.attack.*} family — the six swing-result
 * sounds added in 1.9 that did not exist in 1.7.10 or 1.8.9. Cosmetic only;
 * no gameplay state is touched. Default OFF (era-exact no-op).
 */
public record AttackSoundSettings(boolean enabled) {

    public static final AttackSoundSettings DEFAULTS = new AttackSoundSettings(false);
}
