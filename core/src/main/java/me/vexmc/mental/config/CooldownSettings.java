package me.vexmc.mental.config;

/**
 * The attack-cooldown module switch (config.yml, {@code modules} map —
 * no tunables of its own). When enabled, Mental intercepts the player's own
 * outbound {@code UPDATE_ATTRIBUTES} packet and rewrites the
 * {@code attack_speed} attribute to a value so large that the client's
 * cooldown delay rounds to zero — no charge bar, no greyed-out swing. The
 * server attribute is left at vanilla 4.0, so no plugin conflict arises and
 * no teardown is required. Default OFF (era-exact no-op).
 */
public record CooldownSettings(boolean enabled) {

    public static final CooldownSettings DEFAULTS = new CooldownSettings(false);
}
