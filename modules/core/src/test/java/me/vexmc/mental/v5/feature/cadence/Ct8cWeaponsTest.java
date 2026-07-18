package me.vexmc.mental.v5.feature.cadence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import me.vexmc.mental.kernel.math.Ct8cTables;
import me.vexmc.mental.kernel.math.Ct8cTables.Tier;
import me.vexmc.mental.kernel.math.Ct8cTables.WeaponClass;
import me.vexmc.mental.v5.config.settings.WeaponSpeedSettings;
import me.vexmc.mental.v5.feature.cadence.Ct8cWeapons.Kind;
import org.junit.jupiter.api.Test;

/**
 * Pins the CT8c weapon classifier (spec §2.2): the material-name → weapon-class /
 * tier mapping, and the anti-drift tie between {@link Ct8cWeapons#attributeValue}
 * on defaults and the kernel {@link Ct8cTables#attackSpeed} table (so the local
 * {@code +1.5} offset can never diverge from the ground-truth table).
 */
class Ct8cWeaponsTest {

    private static final WeaponSpeedSettings DEFAULTS = WeaponSpeedSettings.DEFAULTS;

    @Test
    void classifiesEachWeaponClassAndTier() {
        assertEquals(new Kind(WeaponClass.SWORD, Tier.NETHERITE), Ct8cWeapons.classify("NETHERITE_SWORD"));
        assertEquals(new Kind(WeaponClass.AXE, Tier.DIAMOND), Ct8cWeapons.classify("DIAMOND_AXE"));
        assertEquals(new Kind(WeaponClass.PICKAXE, Tier.IRON), Ct8cWeapons.classify("IRON_PICKAXE"));
        assertEquals(new Kind(WeaponClass.SHOVEL, Tier.STONE), Ct8cWeapons.classify("STONE_SHOVEL"));
        assertEquals(new Kind(WeaponClass.HOE, Tier.GOLD), Ct8cWeapons.classify("GOLDEN_HOE"));
        assertEquals(new Kind(WeaponClass.TRIDENT, Tier.NONE), Ct8cWeapons.classify("TRIDENT"));
        assertEquals(new Kind(WeaponClass.SWORD, Tier.WOOD), Ct8cWeapons.classify("WOODEN_SWORD"));
    }

    @Test
    void legacyFlattenedNamesResolveTheSameTier() {
        // Pre-1.13 spellings (WOOD_/GOLD_) must classify identically to the modern ones.
        assertEquals(new Kind(WeaponClass.SWORD, Tier.WOOD), Ct8cWeapons.classify("WOOD_SWORD"));
        assertEquals(new Kind(WeaponClass.HOE, Tier.GOLD), Ct8cWeapons.classify("GOLD_HOE"));
    }

    @Test
    void nonWeaponsAndOutOfTableWeaponsAreBareHand() {
        assertEquals(Ct8cWeapons.FIST, Ct8cWeapons.classify("AIR"));
        assertEquals(Ct8cWeapons.FIST, Ct8cWeapons.classify("DIRT"));
        assertEquals(Ct8cWeapons.FIST, Ct8cWeapons.classify(null));
        assertEquals(Ct8cWeapons.FIST, Ct8cWeapons.classify("MACE")); // out of the §2.2 table
    }

    @Test
    void attacksPerSecondReadsTheOperatorTable() {
        assertEquals(3.0, Ct8cWeapons.attacksPerSecond(Ct8cWeapons.classify("IRON_SWORD"), DEFAULTS));
        assertEquals(2.0, Ct8cWeapons.attacksPerSecond(Ct8cWeapons.classify("DIAMOND_AXE"), DEFAULTS));
        assertEquals(2.5, Ct8cWeapons.attacksPerSecond(Ct8cWeapons.FIST, DEFAULTS));
        // Hoe is the one tier-sensitive class (spec §2.2: 2.0/2.5/3.0/3.5/3.5/3.5).
        assertEquals(2.0, Ct8cWeapons.attacksPerSecond(Ct8cWeapons.classify("WOODEN_HOE"), DEFAULTS));
        assertEquals(3.0, Ct8cWeapons.attacksPerSecond(Ct8cWeapons.classify("IRON_HOE"), DEFAULTS));
        assertEquals(3.5, Ct8cWeapons.attacksPerSecond(Ct8cWeapons.classify("NETHERITE_HOE"), DEFAULTS));
    }

    @Test
    void defaultAttributeValueMatchesTheKernelTable() {
        // Anti-drift: the local +1.5 offset × the operator table must reproduce the
        // kernel's ground-truth Ct8cTables.attackSpeed for every class/tier on defaults.
        assertAttributeMatchesKernel("NETHERITE_SWORD", WeaponClass.SWORD, Tier.NETHERITE);
        assertAttributeMatchesKernel("IRON_SWORD", WeaponClass.SWORD, Tier.IRON);
        assertAttributeMatchesKernel("DIAMOND_AXE", WeaponClass.AXE, Tier.DIAMOND);
        assertAttributeMatchesKernel("IRON_PICKAXE", WeaponClass.PICKAXE, Tier.IRON);
        assertAttributeMatchesKernel("STONE_SHOVEL", WeaponClass.SHOVEL, Tier.STONE);
        assertAttributeMatchesKernel("TRIDENT", WeaponClass.TRIDENT, Tier.NONE);
        assertAttributeMatchesKernel("AIR", WeaponClass.FIST, Tier.NONE);
        assertAttributeMatchesKernel("WOODEN_HOE", WeaponClass.HOE, Tier.WOOD);
        assertAttributeMatchesKernel("IRON_HOE", WeaponClass.HOE, Tier.IRON);
        assertAttributeMatchesKernel("NETHERITE_HOE", WeaponClass.HOE, Tier.NETHERITE);
    }

    private static void assertAttributeMatchesKernel(String material, WeaponClass weaponClass, Tier tier) {
        assertEquals(Ct8cTables.attackSpeed(weaponClass, tier),
                Ct8cWeapons.attributeValue(Ct8cWeapons.classify(material), DEFAULTS),
                1e-9, material + " attribute value must equal the kernel Ct8cTables.attackSpeed");
    }
}
