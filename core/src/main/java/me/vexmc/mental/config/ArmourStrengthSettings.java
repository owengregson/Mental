package me.vexmc.mental.config;

/**
 * The old-armour-strength module switch (config.yml, {@code modules} map).
 *
 * <p>When enabled, the module replaces vanilla's modern toughness-based armour
 * reduction with the pre-1.9 (flat) model: 4% damage reduction per armour point,
 * <strong>no toughness</strong>, applied in the era order armour → resistance →
 * enchant EPF → absorption. Decompile source: {@code pr.java}/{@code acr.java}/
 * {@code ack.java} (decomp-1.8.9), pinned in
 * {@code docs/superpowers/plans/2026-06-14-ocm-ground-truth.md} §4.</p>
 *
 * <p>Era-exact no-op when disabled (default OFF) — zero-touch invariant.</p>
 */
public record ArmourStrengthSettings(boolean enabled) {

    /** Default: module OFF — modern toughness-based armour reduction is unchanged. */
    public static final ArmourStrengthSettings DEFAULTS = new ArmourStrengthSettings(false);
}
