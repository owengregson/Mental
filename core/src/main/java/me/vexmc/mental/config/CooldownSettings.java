package me.vexmc.mental.config;

/**
 * The attack-cooldown module switch (config.yml, {@code modules} map —
 * no tunables of its own). When enabled, Mental removes the 1.9 attack cooldown
 * in two halves: it rewrites the outbound {@code UPDATE_ATTRIBUTES}
 * {@code attack_speed} so the client renders no charge bar / greyed-out swing,
 * AND it raises the player's SERVER {@code attack_speed} base so vanilla
 * {@code Player#attack} deals full-charge damage on every swing (the half that
 * fixes spam-clicked mob / fast-path-off hits, which were scaled to ~20%). The
 * server base is captured and restored on quit/disable, so a disabled module
 * leaves nothing behind. Default OFF (era-exact no-op).
 */
public record CooldownSettings(boolean enabled) {

    public static final CooldownSettings DEFAULTS = new CooldownSettings(false);
}
