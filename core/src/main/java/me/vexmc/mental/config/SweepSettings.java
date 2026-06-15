package me.vexmc.mental.config;

/**
 * The disable-sword-sweep module switch (config.yml, {@code modules} map —
 * no tunables of its own). When enabled, Mental cancels any
 * {@link org.bukkit.event.entity.EntityDamageEvent.DamageCause#ENTITY_SWEEP_ATTACK}
 * damage event (restoring single-target sword damage as it was in 1.7/1.8),
 * and also intercepts outbound {@code PARTICLE} packets to suppress the
 * {@code sweep_attack} particle added alongside the mechanic in 1.9.
 * Default OFF (era-exact no-op).
 */
public record SweepSettings(boolean enabled) {

    public static final SweepSettings DEFAULTS = new SweepSettings(false);
}
