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
}
