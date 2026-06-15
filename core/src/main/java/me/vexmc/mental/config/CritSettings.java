package me.vexmc.mental.config;

/**
 * The old-critical-hits module switch (config.yml, {@code modules} map —
 * no tunables of its own).
 *
 * <p>When enabled, Mental applies the 1.8 era crit rule (×1.5 multiplier)
 * for player melee hits that vanilla missed because the 1.9 sprint/cooldown
 * requirement was not satisfied. This covers only the fast-path-OFF case:
 * when the fast path is ON, {@code DamageCalculator} already handles crits
 * with the era precondition set and this module does nothing.</p>
 *
 * <p>Era truth: 1.8 critted whenever the attacker was falling, airborne, off
 * a ladder, out of water, sighted and unmounted — sprinting was never an
 * exclusion and there was no attack cooldown. 1.9 added two extra requirements
 * (cooldown &gt; 0.9, not sprinting) that vanilla retains on modern servers.
 * The gap this module closes is exactly those two 1.9 additions.</p>
 *
 * <p>Era-exact no-op when disabled (default OFF) — zero-touch invariant.</p>
 */
public record CritSettings(boolean enabled) {

    /** Default: module OFF — vanilla 1.9 crit rules are left untouched. */
    public static final CritSettings DEFAULTS = new CritSettings(false);
}
