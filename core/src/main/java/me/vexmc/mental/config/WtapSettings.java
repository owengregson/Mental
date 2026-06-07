package me.vexmc.mental.config;

/**
 * The wtap-registration module switch (config.yml, {@code modules} map —
 * no tunables of its own). Enabled, the hit-registration fast path reads
 * the attacker's sprint state in packet-arrival order — the era queue's
 * contract, under which a w-tap or s-tap registers no matter how little
 * wall-clock separates it from the follow-up attack. Disabled, the fast
 * path falls back to the tick-frozen snapshot, the pre-1.7.0 behavior.
 */
public record WtapSettings(boolean enabled) {

    static final WtapSettings DEFAULTS = new WtapSettings(true);
}
