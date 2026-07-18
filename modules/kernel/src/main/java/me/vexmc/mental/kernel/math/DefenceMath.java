package me.vexmc.mental.kernel.math;

/**
 * Pure 1.8 armour defence math — no Bukkit dependencies, fully unit-testable.
 *
 * <p>Implements the pre-1.9 (flat) damage-reduction pipeline in the era order:
 * <strong>armour points → resistance → enchant EPF → absorption</strong>. Every
 * constant and formula is pinned from the decompiled 1.8.9 sources cited in
 * {@code docs/superpowers/plans/2026-06-14-ocm-ground-truth.md} §4
 * ({@code pr.java}, {@code acr.java}, {@code ack.java}).</p>
 *
 * <h2>What this deliberately does NOT do</h2>
 * <p><strong>Toughness is ignored.</strong> The 1.8 model has no armour
 * toughness; the modern formula
 * {@code effArmor = clamp(armor - dmg/(2+tough/4), armor*0.2, 20)}
 * (decomp-1.21.11 {@code CombatRules.java}) is exactly what this overrides.
 * Armour points reduce damage at a flat 4% each, full stop.</p>
 *
 * <h2>EPF determinism</h2>
 * <p>The era randomizes the enchantment protection factor:
 * {@code (epf+1>>1) + nextInt((epf>>1)+1)} before the final clamp to 20
 * ({@code ack.java:116}). This module computes EPF <em>deterministically</em> —
 * {@code epf = min(sumRawEPF, 20)} — skipping the random step. This is correct
 * for feel because <strong>clients never predict armour reduction</strong>: the
 * server is the sole authority on post-armour damage, so the per-hit random
 * jitter is invisible to the player. A deterministic EPF is also unit-testable
 * (the pins above are exact), and OCM itself offers a deterministic mode
 * ({@code randomness: false}). The 80% enchant cap (EPF 20 → 0.04×20) is
 * preserved by the clamp.</p>
 */
public final class DefenceMath {

    /** Armour divider: {@code 25.0} ⇒ 0.04 reduction per armour point. [pr.java:712] */
    public static final double ARMOUR_DIVIDER = 25.0;

    /** Reduction per resistance level: {@code 0.2} (level 5 ⇒ 100%). [pr.java:724] */
    public static final double REDUCTION_PER_RESISTANCE_LEVEL = 0.2;

    /** Reduction per EPF point: {@code 0.04} (EPF 20 ⇒ 80%). [pr.java:733-735] */
    public static final double REDUCTION_PER_EPF = 0.04;

    /** Maximum effective EPF after clamping (⇒ 80% enchant reduction). [pr.java:733-735] */
    public static final int MAX_EPF = 20;

    /** Maximum armour points the divider recognises (full immunity at 25). */
    public static final int MAX_ARMOUR_POINTS = 25;

    /* Enchantment type modifiers — floor((6+level²)/3 * modifier). [acr.java:40-54] */

    /** Protection type modifier {@code 0.75}. [acr.java:42] */
    public static final double PROTECTION_MODIFIER = 0.75;

    /** Fire Protection type modifier {@code 1.25}. [acr.java:45] */
    public static final double FIRE_PROTECTION_MODIFIER = 1.25;

    /** Feather Falling type modifier {@code 2.5}. [acr.java:48] */
    public static final double FEATHER_FALLING_MODIFIER = 2.5;

    /** Blast Protection type modifier {@code 1.5}. [acr.java:51] */
    public static final double BLAST_PROTECTION_MODIFIER = 1.5;

    /** Projectile Protection type modifier {@code 1.5}. [acr.java:54] */
    public static final double PROJECTILE_PROTECTION_MODIFIER = 1.5;

    private DefenceMath() {}

    /* ------------------------------------------------------------------ */
    /*  Individual stages                                                  */
    /* ------------------------------------------------------------------ */

    /**
     * Armour-points reduction: {@code damage * (25 - clamp(points,0,25)) / 25}.
     * Flat 4% per point, no toughness. [pr.java:709-712]
     *
     * @param damage       incoming damage (post hard-hat / blocking)
     * @param armourPoints worn armour defence points (clamped to [0, 25])
     * @return damage remaining after armour
     */
    public static double armourReduced(double damage, int armourPoints) {
        int points = clamp(armourPoints, 0, MAX_ARMOUR_POINTS);
        return damage * (MAX_ARMOUR_POINTS - points) / ARMOUR_DIVIDER;
    }

    /**
     * Resistance reduction: {@code damage * (1 - 0.2*level)}, clamped ≥ 0
     * (level 5 ⇒ 100% immunity). [pr.java:724]
     *
     * @param damage          incoming damage (post armour)
     * @param resistanceLevel resistance effect level (amplifier + 1; 0 = none)
     * @return damage remaining after resistance
     */
    public static double resistanceReduced(double damage, int resistanceLevel) {
        if (resistanceLevel <= 0) {
            return damage;
        }
        double factor = Math.min(1.0, resistanceLevel * REDUCTION_PER_RESISTANCE_LEVEL);
        return Math.max(0.0, damage * (1.0 - factor));
    }

    /**
     * Enchant reduction: {@code damage * (1 - 0.04*epf)} (EPF already clamped to
     * [0, 20] by {@link #clampEpf}). [pr.java:733-735]
     *
     * @param damage incoming damage (post resistance)
     * @param epf    clamped enchantment protection factor
     * @return damage remaining after enchantment protection
     */
    public static double enchantReduced(double damage, int epf) {
        int e = clamp(epf, 0, MAX_EPF);
        return Math.max(0.0, damage * (1.0 - REDUCTION_PER_EPF * e));
    }

    /**
     * Absorption: soaks up to {@code absorption} HP of the remaining damage,
     * never producing negative damage. Applied <strong>last</strong> in the era
     * pipeline.
     *
     * @param damage     incoming damage (post enchant)
     * @param absorption the victim's current absorption HP
     * @return damage remaining after absorption
     */
    public static double absorptionReduced(double damage, double absorption) {
        if (absorption <= 0.0) {
            return damage;
        }
        return Math.max(0.0, damage - absorption);
    }

    /* ------------------------------------------------------------------ */
    /*  EPF computation                                                    */
    /* ------------------------------------------------------------------ */

    /**
     * Per-piece enchantment protection factor for a given level and type
     * modifier: {@code floor((6 + level²) / 3.0 * typeModifier)}. [acr.java:40]
     *
     * @param level        the enchantment level on the piece (0 ⇒ 0 EPF)
     * @param typeModifier the protection type modifier (e.g. {@link #PROTECTION_MODIFIER})
     * @return the EPF contribution of that piece for that enchantment
     */
    public static int epf(int level, double typeModifier) {
        if (level <= 0) {
            return 0;
        }
        return (int) Math.floor((6 + level * level) / 3.0 * typeModifier);
    }

    /** Convenience: per-piece EPF for Protection of the given level. */
    public static int protectionEpf(int level) {
        return epf(level, PROTECTION_MODIFIER);
    }

    /**
     * Clamps a summed raw EPF to the era visible cap of 20.
     *
     * <p>The era first clamps the raw sum to 25 ({@code ack.java:111-114}), then
     * randomizes ({@code ack.java:116}), then clamps the result to 20
     * ({@code pr.java:733-735}). Deterministically the random step is skipped
     * (see the class doc), so a single clamp to 20 captures the visible cap.</p>
     */
    public static int clampEpf(int summedRawEpf) {
        return Math.min(summedRawEpf, MAX_EPF);
    }

    /* ------------------------------------------------------------------ */
    /*  Full pipeline                                                      */
    /* ------------------------------------------------------------------ */

    /**
     * Applies the full 1.8 defence pipeline in the era order:
     * armour → resistance → enchant → absorption.
     *
     * @param rawDamage       the damage entering the armour stage (vanilla's
     *                        post hard-hat / post blocking value)
     * @param armourPoints    worn armour defence points
     * @param resistanceLevel resistance effect level (amplifier + 1; 0 = none)
     * @param epf             the clamped enchantment protection factor (from
     *                        {@link #clampEpf} over per-piece {@link #epf} sums)
     * @param absorption      the victim's current absorption HP
     * @return the final damage the victim should take
     */
    public static double finalDamage(
            double rawDamage, int armourPoints, int resistanceLevel, int epf, double absorption) {
        double damage = armourReduced(rawDamage, armourPoints);
        damage = resistanceReduced(damage, resistanceLevel);
        // The era skips the enchant stage once damage hits 0 (ack.java); since
        // enchantReduced is monotone and clamps ≥ 0, applying it to 0 is a no-op,
        // so we apply it unconditionally for clarity.
        damage = enchantReduced(damage, epf);
        damage = absorptionReduced(damage, absorption);
        return damage;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
