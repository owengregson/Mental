package me.vexmc.mental.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
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

    /**
     * Every GUI icon name the flattening renamed maps to the exact pre-1.13
     * constant verified present on the real 1.9.4 / 1.12.2 enum. The alias branch
     * only fires on a pre-flattening server (the boot suite covers that live); here
     * the TABLE is pinned so a renamed glyph can never silently fall to stone.
     */
    @Test
    void renamedIconNamesAliasToTheirPreFlatteningConstant() {
        assertEquals("IRON_FENCE", MenuMaterials.legacyAlias("IRON_BARS"));
        assertEquals("INK_SACK", MenuMaterials.legacyAlias("LIME_DYE"));
        assertEquals("DIODE", MenuMaterials.legacyAlias("REPEATER"));
        assertEquals("PISTON_BASE", MenuMaterials.legacyAlias("PISTON"));
        assertEquals("WATCH", MenuMaterials.legacyAlias("CLOCK"));
        assertEquals("SNOW_BALL", MenuMaterials.legacyAlias("SNOWBALL"));
        assertEquals("SIGN", MenuMaterials.legacyAlias("OAK_SIGN"));
        assertEquals("BOOK_AND_QUILL", MenuMaterials.legacyAlias("WRITABLE_BOOK"));
        assertEquals("REDSTONE_TORCH_ON", MenuMaterials.legacyAlias("REDSTONE_TORCH"));
        assertEquals("GOLD_SWORD", MenuMaterials.legacyAlias("GOLDEN_SWORD"));
        assertEquals("EYE_OF_ENDER", MenuMaterials.legacyAlias("ENDER_EYE"));
        assertEquals("STAINED_GLASS_PANE", MenuMaterials.legacyAlias("GRAY_STAINED_GLASS_PANE"));
    }

    /** Icon names that survived the flattening unchanged must NOT carry an alias. */
    @Test
    void unrenamedIconNamesHaveNoAlias() {
        for (String name : List.of("ARROW", "BARRIER", "COMPASS", "NETHER_STAR",
                "IRON_SWORD", "STONE_SWORD", "DIAMOND_SWORD", "IRON_AXE", "DIAMOND_AXE",
                "BOW", "PAPER", "FISHING_ROD")) {
            assertNull(MenuMaterials.legacyAlias(name), name + " should not need an alias");
        }
    }
}
