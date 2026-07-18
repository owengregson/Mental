package me.vexmc.mental.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * The pane-colour vocabulary contract. The 1.17.1 test classpath IS a modern
 * (post-flattening) enum, so every colour's modern name resolves and the pane
 * resolver takes its modern branch here; the pre-1.13 data-value branch is
 * exercised live by BootSuite on real legacy servers.
 */
class PaneColorTest {

    @Test
    void sixteenColoursWithUniqueDataValuesZeroToFifteen() {
        assertEquals(16, PaneColor.values().length);
        Set<Short> seen = new HashSet<>();
        for (PaneColor color : PaneColor.values()) {
            short data = color.legacyData();
            assertTrue(data >= 0 && data <= 15, color + " data out of 0..15");
            assertTrue(seen.add(data), color + " duplicates data value " + data);
        }
        assertEquals(16, seen.size());
    }

    @Test
    void modernNamesResolveOnTheCompileFloorEnum() {
        for (PaneColor color : PaneColor.values()) {
            assertNotNull(Material.getMaterial(color.modernName()),
                    color + " modern name should resolve on the modern test enum");
        }
    }

    @Test
    void paneBuildsTheModernMaterialHere() {
        // The type name IS the durability-0 proof headlessly: the modern branch
        // uses the single-arg `new ItemStack(Material)` ctor (no data value ever),
        // so a type of the per-colour material — never STONE (fallthrough) and
        // never STAINED_GLASS_PANE (the pre-1.13 data branch) — means the modern,
        // data-free stack was built. The literal getDurability()==0 read routes
        // through Bukkit.getItemFactory() (absent in this headless JVM); the live
        // durability assertion is BootSuite's, on real servers (plan §7.2).
        for (PaneColor color : PaneColor.values()) {
            var stack = MenuMaterials.pane(color);
            assertEquals(color.modernName(), stack.getType().name(), color + " built the wrong material");
        }
    }
}
