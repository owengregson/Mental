package me.vexmc.mental.config;

/**
 * The old-potion-durations module switch (config.yml, {@code modules} map).
 *
 * <p>When enabled, the module restores the pre-1.9 (1.8) potion durations on
 * drink, splash/lingering throw, and dispense. 1.9 shortened many potion
 * durations; this rewrites the consumed/thrown/dispensed potion's effect back to
 * the 1.8 length. The duration table is OCM's
 * {@code old-potion-effects.potion-durations} (seconds), converted to ticks; see
 * {@code me.vexmc.mental.module.potion.PotionDurations}.</p>
 *
 * <p>This is the DURATION half only. Strength/Weakness damage <em>values</em> are
 * a separate concern (see {@code old-armour-strength} / the weakness task) and
 * are NOT touched here.</p>
 *
 * <p>Era-exact no-op when disabled (default OFF) — zero-touch invariant.</p>
 */
public record PotionDurationSettings(boolean enabled) {

    /** Default: module OFF — modern potion durations are unchanged. */
    public static final PotionDurationSettings DEFAULTS = new PotionDurationSettings(false);
}
