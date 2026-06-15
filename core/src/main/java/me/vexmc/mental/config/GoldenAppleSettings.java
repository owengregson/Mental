package me.vexmc.mental.config;

/**
 * The old-golden-apples module switch (config.yml, {@code modules} map).
 *
 * <p>When enabled, the module:</p>
 * <ul>
 *   <li>Overrides modern golden apple and enchanted golden apple potion effects
 *       with the exact 1.8.9 era tables (Regeneration II 100 t + Absorption I
 *       2400 t for the normal apple; Regen V 600 t + Resistance I 6000 t +
 *       Fire Resistance I 6000 t + Absorption I 2400 t for the notch apple).</li>
 *   <li>Registers the notch-apple crafting recipe (8 gold BLOCKS + 1 apple →
 *       ENCHANTED_GOLDEN_APPLE) that was removed in 1.9, and deregisters it
 *       when the module is disabled.</li>
 * </ul>
 *
 * <p>Era-exact no-op when disabled (default OFF) — zero-touch invariant.</p>
 */
public record GoldenAppleSettings(boolean enabled) {

    /** Default: module OFF — modern apple behaviour is unchanged. */
    public static final GoldenAppleSettings DEFAULTS = new GoldenAppleSettings(false);
}
