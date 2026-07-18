package me.vexmc.mental.kernel.math;

/**
 * 1.7.10 damage for the fast path, in the 1.7.10 order: weapon damage,
 * times 1.5 in a critical posture, <em>then</em> the Sharpness bonus —
 * crits never multiplied enchantment damage before 1.9. Armor,
 * invulnerability, knockback and feedback all still flow through the
 * vanilla hurt chain at the damage call.
 *
 * <p>The 1.9 combat update also rebalanced every tool tier (swords lost a
 * point, axes became heavy weapons); {@link #weaponDamage} restores the
 * pre-1.9 tables — sword {@code 4+tier}, axe {@code 3+tier}, pickaxe
 * {@code 2+tier}, shovel {@code 1+tier}, plus the 1.0 base hand damage.</p>
 *
 * <p>The 1.9 combat update also weakened Strength and strengthened Weakness:
 * era Strength was a <em>multiplicative</em> bonus on the weapon base
 * ({@code factor = 1 + 2.5×(amp+1)} — Strength I ×3.5, Strength II ×6.0,
 * MULTIPLY_TOTAL [pe.java:17]) versus 1.9's flat {@code +3×(amp+1)} ADD; era
 * Weakness subtracted {@code 2.0×(amp+1)} [pe.java:30 / rv.java:28] versus
 * 1.9's {@code 4.0×(amp+1)}. These era values are applied to the <em>weapon
 * base</em> — before the crit ×1.5 and before the Sharpness additive
 * [wn.java:761-764]: {@code eraBase = (weaponBase × strengthFactor) −
 * weaknessReduction}, clamped ≥ 0.</p>
 *
 * <p>This class is the pure half of the original {@code DamageCalculator};
 * the entity-reading and reflection shells (attribute reads, potion lookup,
 * the OCM vanilla-shape composition, the crit posture predicate) stay in
 * core.</p>
 */
public final class DamageTables {

    private DamageTables() {}

    /** The era critical multiplier, applied to the weapon base BEFORE the Sharpness additive. */
    public static double critMultiplier() {
        return 1.5;
    }

    /**
     * The era Strength multiplier on the weapon base: {@code 1 + 2.5×(amp+1)}
     * (MULTIPLY_TOTAL [pe.java:17]). Strength I (amp 0) → ×3.5, Strength II
     * (amp 1) → ×6.0. An amplifier below 0 means "no Strength" → ×1.
     */
    public static double strengthFactor(int amplifier) {
        return amplifier < 0 ? 1.0 : 1.0 + 2.5 * (amplifier + 1);
    }

    /**
     * The era Weakness reduction on the weapon base: {@code 2.0×(amp+1)}
     * (ADD [pe.java:30 / rv.java:28]). Weakness I (amp 0) → 2.0. An amplifier
     * below 0 means "no Weakness" → 0.
     */
    public static double weaknessReduction(int amplifier) {
        return amplifier < 0 ? 0.0 : 2.0 * (amplifier + 1);
    }

    /**
     * Applies the era Strength factor then the era Weakness reduction to a pure
     * weapon base, in the era order [wn.java:761-764], clamped ≥ 0. Both effects
     * are gated by their amplifier ({@code < 0} = absent).
     */
    public static double eraPotionBase(double weaponBase, int strengthAmp, int weaknessAmp) {
        double value = weaponBase * strengthFactor(strengthAmp) - weaknessReduction(weaknessAmp);
        return Math.max(0.0, value);
    }

    /**
     * Recovers the pure weapon base from a modern attack-damage attribute value,
     * which already folds in MODERN Strength ({@code +3×(amp+1)}) and Weakness
     * ({@code −4×(amp+1)}). Subtracting the modern Strength and adding back the
     * modern Weakness leaves the bare weapon base so the era factors can replace
     * them. Both effects are gated by their amplifier ({@code < 0} = absent).
     *
     * <p>Limitation: this assumes the attribute carries exactly vanilla's modern
     * potion modifiers and no third-party attribute modifiers for these effects.
     * The dominant fast-path scenario uses the legacy-tool path (where the base is
     * already pure), so this recovery only matters when {@code legacyToolDamage}
     * is off.</p>
     */
    public static double recoverPureBase(double attrValue, int strengthAmp, int weaknessAmp) {
        double pure = attrValue;
        if (strengthAmp >= 0) {
            pure -= 3.0 * (strengthAmp + 1);
        }
        if (weaknessAmp >= 0) {
            pure += 4.0 * (weaknessAmp + 1);
        }
        return pure;
    }

    /** Pre-1.9 Sharpness: {@code 1.25 × level} (1.9 changed it to {@code 0.5 × level + 0.5}). */
    public static double sharpnessBonus(int level) {
        return level <= 0 ? 0.0 : 1.25 * level;
    }

    /** The 1.9+ Sharpness vanilla composes with: {@code 1 + 0.5 × (level − 1)}. */
    public static double vanillaSharpnessBonus(int level) {
        return level <= 0 ? 0.0 : 1.0 + 0.5 * (level - 1);
    }

    /**
     * Pre-1.9 total attack damage (base hand 1.0 included) for the four
     * legacy tool classes, or null for anything whose modern attribute
     * already matches the era (hands, hoes) or postdates it. Keyed by the
     * weapon's EFFECTIVE material enum-constant name (the neutral
     * combat:effective_material marker when present, else its own type).
     */
    public static Double weaponDamage(String effectiveMaterialName) {
        String name = effectiveMaterialName;
        double tool;
        if (name.endsWith("_SWORD")) {
            tool = 4.0;
        } else if (name.endsWith("_PICKAXE")) {
            tool = 2.0;
        } else if (name.endsWith("_AXE")) {
            tool = 3.0;
        } else if (name.endsWith("_SHOVEL")) {
            tool = 1.0;
        } else {
            return null;
        }
        double tier;
        if (name.startsWith("WOODEN_") || name.startsWith("GOLDEN_")) {
            tier = 0.0;
        } else if (name.startsWith("STONE_")) {
            tier = 1.0;
        } else if (name.startsWith("IRON_")) {
            tier = 2.0;
        } else if (name.startsWith("DIAMOND_")) {
            tier = 3.0;
        } else if (name.startsWith("NETHERITE_")) {
            tier = 4.0; // extrapolated: one above diamond, the modern tier pattern
        } else {
            return null;
        }
        return 1.0 + tool + tier;
    }
}
