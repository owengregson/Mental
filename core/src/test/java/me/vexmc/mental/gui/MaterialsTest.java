package me.vexmc.mental.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class MaterialsTest {

    @Test
    void resolvesKnownMaterialsByName() {
        assertEquals(Material.IRON_SWORD, Materials.of("IRON_SWORD"));
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, Materials.of("GRAY_STAINED_GLASS_PANE"));
    }

    @Test
    void unknownNamesFallBackToAStaple() {
        assertEquals(Materials.FALLBACK, Materials.of("NOT_A_REAL_MATERIAL_XYZ"));
        assertEquals(Material.STONE, Materials.FALLBACK); // the fallback must itself be ancient
    }

    @Test
    void resolutionIsCachedAndStable() {
        assertSame(Materials.of("DIAMOND_SWORD"), Materials.of("DIAMOND_SWORD"));
    }
}
