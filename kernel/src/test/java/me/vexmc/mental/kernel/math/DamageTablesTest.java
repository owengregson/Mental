package me.vexmc.mental.kernel.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * The pure-math half of the original DamageCalculatorTest: every expected
 * value is byte-identical; material parameters arrive as enum-name strings
 * (the kernel's Material seam).
 */
class DamageTablesTest {

    @Test
    void sharpnessFollowsPreNineVanilla() {
        assertEquals(0.0, DamageTables.sharpnessBonus(0));
        assertEquals(0.0, DamageTables.sharpnessBonus(-3));
        assertEquals(1.25, DamageTables.sharpnessBonus(1));
        assertEquals(2.5, DamageTables.sharpnessBonus(2));
        assertEquals(3.75, DamageTables.sharpnessBonus(3));
        assertEquals(6.25, DamageTables.sharpnessBonus(5));
    }

    @Test
    void legacySwordDamageIsFourPlusTier() {
        assertEquals(5.0, DamageTables.weaponDamage("WOODEN_SWORD"));
        assertEquals(5.0, DamageTables.weaponDamage("GOLDEN_SWORD"));
        assertEquals(6.0, DamageTables.weaponDamage("STONE_SWORD"));
        assertEquals(7.0, DamageTables.weaponDamage("IRON_SWORD"));
        assertEquals(8.0, DamageTables.weaponDamage("DIAMOND_SWORD"));
        assertEquals(9.0, DamageTables.weaponDamage("NETHERITE_SWORD"));
    }

    @Test
    void legacyAxeDamageIsThreePlusTierNotAHeavyWeapon() {
        assertEquals(4.0, DamageTables.weaponDamage("WOODEN_AXE"));
        assertEquals(5.0, DamageTables.weaponDamage("STONE_AXE"));
        assertEquals(6.0, DamageTables.weaponDamage("IRON_AXE"));
        assertEquals(7.0, DamageTables.weaponDamage("DIAMOND_AXE"));
    }

    @Test
    void legacyPickaxeAndShovelTablesHold() {
        assertEquals(3.0, DamageTables.weaponDamage("WOODEN_PICKAXE"));
        assertEquals(6.0, DamageTables.weaponDamage("DIAMOND_PICKAXE"));
        assertEquals(2.0, DamageTables.weaponDamage("WOODEN_SHOVEL"));
        assertEquals(5.0, DamageTables.weaponDamage("DIAMOND_SHOVEL"));
    }

    @Test
    void nonToolsFallThroughToTheAttribute() {
        assertNull(DamageTables.weaponDamage("AIR"));
        assertNull(DamageTables.weaponDamage("STICK"));
        assertNull(DamageTables.weaponDamage("WOODEN_HOE"));
        assertNull(DamageTables.weaponDamage("FISHING_ROD"));
    }

    @Test
    void eraStrengthFactorIsMultiplicative() {
        // 1.8 Strength is MULTIPLY_TOTAL on the weapon base: factor 1 + 2.5(amp+1).
        // [pe.java:17] — far stronger than 1.9's flat +3.0(amp+1) ADD.
        assertEquals(1.0, DamageTables.strengthFactor(-1)); // no effect → ×1
        assertEquals(3.5, DamageTables.strengthFactor(0));  // Strength I → ×3.5
        assertEquals(6.0, DamageTables.strengthFactor(1));  // Strength II → ×6.0
        assertEquals(8.5, DamageTables.strengthFactor(2));  // Strength III → ×8.5
    }

    @Test
    void eraWeaknessReductionIsTwoPerLevel() {
        // 1.8 Weakness is ADD on the weapon base: -2.0(amp+1). [pe.java:30 / rv.java:28]
        // 1.9 doubled it to -4.0(amp+1).
        assertEquals(0.0, DamageTables.weaknessReduction(-1)); // no effect → 0
        assertEquals(2.0, DamageTables.weaknessReduction(0));  // Weakness I → -2.0
        assertEquals(4.0, DamageTables.weaknessReduction(1));  // Weakness II → -4.0
    }

    @Test
    void eraPotionBaseAppliesStrengthThenWeaknessBeforeCrit() {
        // [wn.java:761-764] order: era potion values modify the weapon base
        // BEFORE the crit ×1.5 and BEFORE the sharpness additive. Diamond sword
        // legacy base 7.
        double diamondSword = 7.0;

        // Strength I: 7 × 3.5 = 24.5 (pre-crit).
        assertEquals(24.5, DamageTables.eraPotionBase(diamondSword, 0, -1));
        // Strength II: 7 × 6.0 = 42.0.
        assertEquals(42.0, DamageTables.eraPotionBase(diamondSword, 1, -1));
        // Weakness I only: 7 − 2.0 = 5.0.
        assertEquals(5.0, DamageTables.eraPotionBase(diamondSword, -1, 0));
        // Strength I + Weakness I combined: (7 × 3.5) − 2.0 = 22.5.
        assertEquals(22.5, DamageTables.eraPotionBase(diamondSword, 0, 0));
        // No effects → base unchanged.
        assertEquals(7.0, DamageTables.eraPotionBase(diamondSword, -1, -1));
    }

    @Test
    void eraPotionBaseClampsAtZero() {
        // Weakness on a tiny base must not drive damage negative.
        // Wooden shovel legacy base 2; Weakness II = -4.0 → clamp to 0.
        assertEquals(0.0, DamageTables.eraPotionBase(2.0, -1, 1));
        // Bare hand (1.0) + Weakness I (-2.0) → clamp to 0.
        assertEquals(0.0, DamageTables.eraPotionBase(1.0, -1, 0));
    }

    @Test
    void recoverPureBaseStripsModernStrengthAndWeakness() {
        // Attribute path: the attribute value already folds in modern Strength
        // (+3×(amp+1)) and Weakness (−4×(amp+1)). Recovering the pure weapon base
        // undoes those so the era factors apply to the bare weapon.
        // Diamond sword attribute 7, Strength I modern adds +3 → attr 10; recover 7.
        assertEquals(7.0, DamageTables.recoverPureBase(10.0, 0, -1));
        // Strength II modern adds +6 → attr 13; recover 7.
        assertEquals(7.0, DamageTables.recoverPureBase(13.0, 1, -1));
        // Weakness I modern subtracts 4 → attr 3; recover 7.
        assertEquals(7.0, DamageTables.recoverPureBase(3.0, -1, 0));
        // No effects → attribute is already the pure base.
        assertEquals(7.0, DamageTables.recoverPureBase(7.0, -1, -1));
    }

    @Test
    void vanillaSharpnessMatchesWhatOcmDecomposes() {
        // OCM subtracts DamageUtils.getNewSharpnessDamage(level) — 1 + 0.5(l−1)
        // — from the raw damage; the vanilla-shape composition must add the
        // exact same series or the decomposition drifts.
        assertEquals(0.0, DamageTables.vanillaSharpnessBonus(0));
        assertEquals(0.0, DamageTables.vanillaSharpnessBonus(-1));
        assertEquals(1.0, DamageTables.vanillaSharpnessBonus(1));
        assertEquals(1.5, DamageTables.vanillaSharpnessBonus(2));
        assertEquals(2.0, DamageTables.vanillaSharpnessBonus(3));
        assertEquals(3.0, DamageTables.vanillaSharpnessBonus(5));
    }

    @Test
    void critMultiplierIsTheEraOneAndAHalf() {
        // New pin (the ×1.5 lived inline in DamageCalculator.calculate, which
        // stays in core): crits multiplied the weapon base by 1.5 BEFORE the
        // Sharpness additive on every pre-1.9 version.
        assertEquals(1.5, DamageTables.critMultiplier());
    }
}
