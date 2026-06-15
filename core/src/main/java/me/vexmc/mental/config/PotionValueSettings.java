package me.vexmc.mental.config;

/**
 * The old-potion-values module switch (config.yml, {@code modules} map —
 * no tunables of its own).
 *
 * <p>When enabled, Mental restores the pre-1.9 (1.8) Strength/Weakness damage
 * <em>VALUES</em> on fast-path melee hits. Era Strength was a multiplicative
 * bonus on the weapon base ({@code ×3.5} at Strength I, {@code ×6.0} at
 * Strength II — far stronger than 1.9's flat {@code +3} ADD) and era Weakness
 * subtracted {@code 2.0} per level (vs 1.9's {@code 4.0}). These are applied to
 * the weapon base before the crit ×1.5 and the Sharpness additive, in
 * {@code me.vexmc.mental.module.hitreg.DamageCalculator}.</p>
 *
 * <p>This is the VALUES half only. Potion <em>durations</em> are a separate
 * concern (see {@code old-potion-durations}) and are NOT touched here.</p>
 *
 * <p><b>Scope:</b> era values apply only to the fast-path melee hits Mental
 * registers (the dominant PvP case). Mob attacks and fast-path-off hits keep
 * vanilla Strength/Weakness — an accepted scope trade-off, not an
 * attribute-modifier lifecycle override.</p>
 *
 * <p>Era-exact no-op when disabled (default OFF) — zero-touch invariant.</p>
 */
public record PotionValueSettings(boolean enabled) {

    /** Default: module OFF — modern Strength/Weakness damage values are unchanged. */
    public static final PotionValueSettings DEFAULTS = new PotionValueSettings(false);
}
