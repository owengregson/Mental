package me.vexmc.mental.v5.feature.damage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The fast-path composition arithmetic (fixed legacy order, era pins).
 */
class DamageShaperTest {

    /* --------------------------- composition pins --------------------------- */

    @Test
    void legacyDiamondSwordSharpnessFiveIsFourteenPointTwoFive() {
        // Diamond sword legacy base 8.0 + Sharpness 1.25×5 = 6.25; no crit, no potion.
        assertEquals(14.25, DamageShaper.composeLegacy(8.0, -1, -1, false, false, 5), 1e-9,
                "the era pin: sharpness-5 diamond legacy-composes to 14.25");
    }

    @Test
    void legacyCritMultipliesTheWeaponBaseBeforeTheSharpnessAdditive() {
        // (8 × 1.5) + 6.25 = 18.25 — crit never multiplies the Sharpness additive.
        assertEquals(18.25, DamageShaper.composeLegacy(8.0, -1, -1, false, true, 5), 1e-9);
    }

    @Test
    void legacyStrengthOneMultipliesTheWeaponBaseBeforeSharpness() {
        // Strength I (amp 0) ×3.5 on the base 8 = 28, + Sharpness 6.25 = 34.25.
        assertEquals(34.25, DamageShaper.composeLegacy(8.0, 0, -1, true, false, 5), 1e-9);
    }

    /* --------------------------- CT8c composition pins (Task D) --------------------------- */

    @Test
    void ct8cToolBaseReadsTheCt8cTableFromTheEffectiveMaterialName() {
        // Spec §2.2: netherite sword 7 (2 base + 2 sword + 3 netherite tier),
        // diamond hoe 3 (2 base + 1 hoe-diamond flat), fist 2 (bare base).
        assertEquals(7.0, DamageShaper.ct8cToolBase("NETHERITE_SWORD"), 1e-9);
        assertEquals(3.0, DamageShaper.ct8cToolBase("DIAMOND_HOE"), 1e-9);
        assertEquals(2.0, DamageShaper.ct8cToolBase("AIR"), 1e-9);
    }

    @Test
    void ct8cToolBaseDisambiguatesPickaxeFromAxeBySuffixOrder() {
        // "_PICKAXE" also ends with "_AXE" — the pickaxe suffix must be tested
        // first. Diamond pickaxe 5 (2+1+2), diamond axe 7 (2+3+2), spec §2.2.
        assertEquals(5.0, DamageShaper.ct8cToolBase("DIAMOND_PICKAXE"), 1e-9);
        assertEquals(7.0, DamageShaper.ct8cToolBase("DIAMOND_AXE"), 1e-9);
    }

    @Test
    void ct8cAttackSpeedIsTheWeaponAttributeValue() {
        // Spec §2.2 attribute column: sword 4.5, fist 4.0, diamond hoe 5.0.
        assertEquals(4.5, DamageShaper.ct8cAttackSpeed("DIAMOND_SWORD"), 1e-9);
        assertEquals(4.0, DamageShaper.ct8cAttackSpeed("AIR"), 1e-9);
        assertEquals(5.0, DamageShaper.ct8cAttackSpeed("DIAMOND_HOE"), 1e-9);
    }

    @Test
    void ct8cFoldsSharpnessIntoTheBaseBeforeTheFlatCritMultiplier() {
        // The plan's crit-ordering pin: Sharpness V iron sword crit =
        // (2 + 1 + 2 + 3) × 1.5 = 12 — enchant added BEFORE the ×1.5 (spec §2.3,
        // "vanilla adds it after"). Iron sword base 5, Sharpness V modern bonus 3.
        // No potion present, so the ct8c-potions gate (arg 4) is inert either way.
        assertEquals(8.0, DamageShaper.composeCt8c(5.0, -1, -1, true, false, 5, 0, 0.0), 1e-9,
                "no crit: base 5 + Sharpness V 3 = 8");
        assertEquals(12.0, DamageShaper.composeCt8c(5.0, -1, -1, true, true, 5, 0, 0.0), 1e-9,
                "crit: (5 + 3) × 1.5 = 12");
    }

    @Test
    void ct8cFoldsCleavingAsEnchantDamageBeforeCrit() {
        // Cleaving damage bonus 1+level (spec §2.9): netherite axe base 8 +
        // Cleaving III (+4) = 12, no crit; ×1.5 under crit = 18.
        assertEquals(12.0, DamageShaper.composeCt8c(8.0, -1, -1, true, false, 0, 3, 0.0), 1e-9);
        assertEquals(18.0, DamageShaper.composeCt8c(8.0, -1, -1, true, true, 0, 3, 0.0), 1e-9);
    }

    /* ------------ CT8c Strength/Weakness gating: the three toggle combinations (Task INT wire 3) ------------ */

    @Test
    void ct8cPotionsOnFoldsStrengthWeaknessAsTheCt8cPercentMultipliers() {
        // "both on" (ct8c-damage + ct8c-potions): the ±20%/level MULTIPLY_TOTAL
        // (spec §2.8), delegated to Ct8cPotionValues.apply — MULTIPLICATIVE when a
        // player carries both, so the plan's base-7 pins land exactly.
        assertEquals(8.4, DamageShaper.composeCt8c(7.0, 0, -1, true, false, 0, 0, 0.0), 1e-9,
                "Strength I: 7 × 1.2 = 8.4");
        assertEquals(5.6, DamageShaper.composeCt8c(7.0, -1, 0, true, false, 0, 0, 0.0), 1e-9,
                "Weakness I: 7 × 0.8 = 5.6");
        assertEquals(6.72, DamageShaper.composeCt8c(7.0, 0, 0, true, false, 0, 0, 0.0), 1e-9,
                "both I: 7 × 1.2 × 0.8 = 6.72 (MULTIPLY_TOTAL is sequential, not additive)");
    }

    @Test
    void ct8cPotionsOffPreservesVanillaFlatStrengthRatherThanErasingIt() {
        // "damage-only" (ct8c-damage on, ct8c-potions OFF): the ±20% fold belongs to
        // CT8C_POTIONS, so with it off the table-base overwrite must NOT silently erase
        // the attacker's Strength — it preserves the server's vanilla flat +3/−4
        // (spec §2.8, "replaces vanilla flat +3/−4").
        assertEquals(10.0, DamageShaper.composeCt8c(7.0, 0, -1, false, false, 0, 0, 0.0), 1e-9,
                "Strength I: 7 + 3 = 10 (vanilla flat, preserved)");
        assertEquals(3.0, DamageShaper.composeCt8c(7.0, -1, 0, false, false, 0, 0, 0.0), 1e-9,
                "Weakness I: 7 − 4 = 3 (vanilla flat, preserved)");
        assertEquals(6.0, DamageShaper.composeCt8c(7.0, 0, 0, false, false, 0, 0, 0.0), 1e-9,
                "both I: 7 + 3 − 4 = 6 (vanilla flat, preserved)");
    }

    @Test
    void ct8cFastPathAppliesThePercentFoldWhenPotionsAloneAreEnabled() {
        // "potions-only" (ct8c-potions on, ct8c-damage OFF): ct8c-damage's EDBEE
        // overwrite is inactive, so the ±20% fold rides the fast-path amount seam
        // (composeFastPath) instead — the CT8C_POTIONS gate replacing the era values.
        assertEquals(8.4, DamageShaper.composeFastPath(7.0, 0, -1, true, false, false, 0), 1e-9,
                "Strength I on the fast-path amount: 7 × 1.2 = 8.4");
        assertEquals(6.72, DamageShaper.composeFastPath(7.0, 0, 0, true, false, false, 0), 1e-9,
                "both I on the fast-path amount: 7 × 1.2 × 0.8 = 6.72");
    }

    @Test
    void fastPathIsByteIdenticalToTheLegacyOrderWhenCt8cPotionsAreOff() {
        // Zero-touch regression: ct8c-potions OFF ⇒ composeFastPath is exactly the
        // legacy order it delegates to (era Strength gated on old-potion-values).
        assertEquals(14.25, DamageShaper.composeFastPath(8.0, -1, -1, false, false, false, 5), 1e-9,
                "classic sharpness-5 diamond: 8 + 6.25 = 14.25");
        assertEquals(34.25, DamageShaper.composeFastPath(8.0, 0, -1, false, true, false, 5), 1e-9,
                "era Strength I (old-potion-values on): 8 × 3.5 + 6.25 = 34.25");
    }

    @Test
    void ct8cAddsTheImpalingBonusAsEnchantDamageOnAWetVictim() {
        // Spec §2.9: Impaling now hits ALL wet victims; the wet predicate lives
        // in the unit, the already-resolved 2.5×level bonus folds in here.
        assertEquals(9.5, DamageShaper.composeCt8c(7.0, -1, -1, true, false, 0, 0, 2.5), 1e-9,
                "trident base 7 + Impaling I (2.5) on a wet victim");
    }
}
