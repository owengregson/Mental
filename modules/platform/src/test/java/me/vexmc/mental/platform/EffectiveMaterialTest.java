package me.vexmc.mental.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/** Pure tests for the neutral {@code combat:effective_material} resolution (the Bukkit PDC read is a live shell). */
class EffectiveMaterialTest {

    @Test
    void anExactMaterialNameResolvesToThatMaterial() {
        // A gold sword marked "DIAMOND_SWORD" is treated as a diamond sword for era stats.
        assertEquals(Material.DIAMOND_SWORD, EffectiveMaterial.resolve("DIAMOND_SWORD", Material.GOLDEN_SWORD));
        assertEquals(Material.DIAMOND_CHESTPLATE,
                EffectiveMaterial.resolve("DIAMOND_CHESTPLATE", Material.GOLDEN_CHESTPLATE));
    }

    @Test
    void blankOrNullFallsBackToTheItemType() {
        assertEquals(Material.GOLDEN_SWORD, EffectiveMaterial.resolve(null, Material.GOLDEN_SWORD));
        assertEquals(Material.GOLDEN_SWORD, EffectiveMaterial.resolve("", Material.GOLDEN_SWORD));
        assertEquals(Material.GOLDEN_SWORD, EffectiveMaterial.resolve("   ", Material.GOLDEN_SWORD));
    }

    @Test
    void anUnknownNameFallsBackAndNeverThrows() {
        assertEquals(Material.GOLDEN_SWORD, EffectiveMaterial.resolve("NOT_A_MATERIAL", Material.GOLDEN_SWORD));
        // A material name from a version this server doesn't have degrades to the display type.
        assertEquals(Material.GOLDEN_AXE, EffectiveMaterial.resolve("SOME_FUTURE_ITEM", Material.GOLDEN_AXE));
    }
}
