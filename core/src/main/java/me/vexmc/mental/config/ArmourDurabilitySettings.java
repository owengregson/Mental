package me.vexmc.mental.config;

/**
 * The old-armour-durability module switch (config.yml, {@code modules} map).
 *
 * <p>When enabled, the module restores the 1.8 armour-durability Unbreaking skip
 * probability ({@code 60 + 40/(level+1)}% chance the piece takes wear) in place
 * of modern armour's generic {@code level/(level+1)}, while keeping vanilla's
 * unchanged per-hit magnitude ({@code max(1, floor(damage/4))}). Decompile
 * source: {@code acg.java}/{@code wm.java} (decomp-1.8.9), pinned in
 * {@code docs/superpowers/plans/2026-06-14-ocm-ground-truth.md} §4; mirrors OCM's
 * {@code ModuleOldArmourDurability}.</p>
 *
 * <p>Era-exact no-op when disabled (default OFF) — zero-touch invariant.</p>
 */
public record ArmourDurabilitySettings(boolean enabled) {

    /** Default: module OFF — modern armour Unbreaking probability is unchanged. */
    public static final ArmourDurabilitySettings DEFAULTS = new ArmourDurabilitySettings(false);
}
