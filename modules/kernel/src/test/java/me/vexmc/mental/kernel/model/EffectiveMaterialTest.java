package me.vexmc.mental.kernel.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pure tests for the neutral {@code combat:effective_material} resolution (the Bukkit PDC read is a
 * live shell). The original test's unknown-material case ({@code "NOT_A_MATERIAL"} degrading to the
 * fallback) is NOT ported: deciding a name is unknown requires the host version's Material registry,
 * so that degradation — and its pin — stays with core's shell.
 */
class EffectiveMaterialTest {

    @Test
    void anExactMaterialNameResolvesToThatMaterial() {
        // A gold sword marked "DIAMOND_SWORD" is treated as a diamond sword for era stats.
        assertEquals("DIAMOND_SWORD", EffectiveMaterial.resolve("DIAMOND_SWORD", "GOLDEN_SWORD"));
        assertEquals("DIAMOND_CHESTPLATE",
                EffectiveMaterial.resolve("DIAMOND_CHESTPLATE", "GOLDEN_CHESTPLATE"));
    }

    @Test
    void blankOrNullFallsBackToTheItemType() {
        assertEquals("GOLDEN_SWORD", EffectiveMaterial.resolve(null, "GOLDEN_SWORD"));
        assertEquals("GOLDEN_SWORD", EffectiveMaterial.resolve("", "GOLDEN_SWORD"));
        assertEquals("GOLDEN_SWORD", EffectiveMaterial.resolve("   ", "GOLDEN_SWORD"));
    }
}
