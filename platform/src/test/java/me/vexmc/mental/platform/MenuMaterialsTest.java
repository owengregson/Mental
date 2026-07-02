package me.vexmc.mental.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * The name-probe material contract, lifted verbatim from the retired
 * {@code gui/MaterialsTest} reuse-ledger asset (only the class name moved with
 * its subject to the platform layer).
 */
class MenuMaterialsTest {

    @Test
    void resolvesKnownMaterialsByName() {
        assertEquals(Material.IRON_SWORD, MenuMaterials.of("IRON_SWORD"));
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, MenuMaterials.of("GRAY_STAINED_GLASS_PANE"));
    }

    @Test
    void unknownNamesFallBackToAStaple() {
        assertEquals(MenuMaterials.FALLBACK, MenuMaterials.of("NOT_A_REAL_MATERIAL_XYZ"));
        assertEquals(Material.STONE, MenuMaterials.FALLBACK); // the fallback must itself be ancient
    }

    @Test
    void resolutionIsCachedAndStable() {
        assertSame(MenuMaterials.of("DIAMOND_SWORD"), MenuMaterials.of("DIAMOND_SWORD"));
    }
}
