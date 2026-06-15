package me.vexmc.mental.config;

/**
 * The old-player-regen module switch (config.yml, {@code modules} map).
 *
 * <p>When enabled, the module suppresses the 1.9+ saturated-regen model
 * ({@link org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason#SATIATED})
 * and replaces it with the 1.8 natural regen: 1 HP every 80 ticks (4 s) when
 * {@code foodLevel >= 18}, provided health is between 0 and max and the world
 * gamerule {@code naturalRegeneration} is on. Decompile source: FoodStats/xg.java
 * (decomp-1.8.9).</p>
 *
 * <p>Era-exact no-op when disabled (default OFF) — zero-touch invariant.</p>
 */
public record RegenSettings(boolean enabled) {

    /** Default: module OFF — modern 1.9+ saturated regen is unchanged. */
    public static final RegenSettings DEFAULTS = new RegenSettings(false);
}
