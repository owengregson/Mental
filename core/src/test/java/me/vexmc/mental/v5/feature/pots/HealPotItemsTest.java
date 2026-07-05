package me.vexmc.mental.v5.feature.pots;

import static org.junit.jupiter.api.Assertions.assertEquals;

import me.vexmc.mental.v5.feature.pots.HealPotItems.BaseStrategy;
import me.vexmc.mental.v5.feature.pots.HealPotItems.GlintStrategy;
import org.junit.jupiter.api.Test;

/**
 * The pure cross-version strategy selection for the heal potion — the only part
 * of the item construction that can be pinned off a live server (the Bukkit
 * mutation is integration-verified on the modern tier and self-checked through
 * the same read-back probes). These pins encode which API path each version band
 * takes: 1.20.5+ uses the flattened base type + native glint; below that the
 * legacy PotionData + dummy-enchant classic; and the "neither available"
 * degradation is a defined NONE, not a crash.
 */
class HealPotItemsTest {

    @Test
    void modernBaseTypeWinsWhenSetterAndStrongHealingBothExist() {
        assertEquals(BaseStrategy.MODERN_BASE_TYPE,
                HealPotItems.chooseBase(true, true, true));
        // The modern path needs BOTH the setter and the level-II constant.
        assertEquals(BaseStrategy.MODERN_BASE_TYPE,
                HealPotItems.chooseBase(true, true, false));
    }

    @Test
    void legacyPotionDataWhenTheModernSetterIsAbsent() {
        // Pre-1.20.5: no setBasePotionType, but INSTANT_HEAL is present.
        assertEquals(BaseStrategy.LEGACY_POTION_DATA,
                HealPotItems.chooseBase(false, false, true));
        // Setter present but STRONG_HEALING absent (an in-between shape) still falls to legacy.
        assertEquals(BaseStrategy.LEGACY_POTION_DATA,
                HealPotItems.chooseBase(true, false, true));
    }

    @Test
    void neitherHealingConstantAvailableIsADefinedNone() {
        assertEquals(BaseStrategy.NONE, HealPotItems.chooseBase(false, false, false));
        assertEquals(BaseStrategy.NONE, HealPotItems.chooseBase(true, false, false));
    }

    @Test
    void glintOverrideWhereItExistsElseTheDummyEnchantClassic() {
        assertEquals(GlintStrategy.GLINT_OVERRIDE, HealPotItems.chooseGlint(true));
        assertEquals(GlintStrategy.DUMMY_ENCHANT, HealPotItems.chooseGlint(false));
    }
}
