package me.vexmc.mental.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import me.vexmc.mental.kernel.math.DamageTables;
import org.junit.jupiter.api.Test;

/**
 * Hand-pinned both directions for the flattening normalizer.
 *
 * <p>The pure {@code translate} half is exercised directly: the modern-API test
 * JVM reports a flattened server, so {@link LegacyMaterialNames#modernize} is
 * identity here and only the table lookup reaches the legacy mapping. The
 * end-to-end block pins the whole Q1 seam — a pre-flattening tool name, once
 * translated, keys the kernel's modern-named {@code DamageTables} table to the
 * era pin value (cited from {@code DamageTablesTest}, not re-derived).</p>
 */
class LegacyMaterialNamesTest {

    /* ---- legacy → modern (the combat vocabulary the kernel consumes) ---- */

    @Test
    void woodToolsBecomeWooden() {
        assertEquals("WOODEN_SWORD", LegacyMaterialNames.translate("WOOD_SWORD"));
        assertEquals("WOODEN_AXE", LegacyMaterialNames.translate("WOOD_AXE"));
        assertEquals("WOODEN_PICKAXE", LegacyMaterialNames.translate("WOOD_PICKAXE"));
        assertEquals("WOODEN_HOE", LegacyMaterialNames.translate("WOOD_HOE"));
    }

    @Test
    void goldToolsAndArmourBecomeGolden() {
        assertEquals("GOLDEN_SWORD", LegacyMaterialNames.translate("GOLD_SWORD"));
        assertEquals("GOLDEN_AXE", LegacyMaterialNames.translate("GOLD_AXE"));
        assertEquals("GOLDEN_PICKAXE", LegacyMaterialNames.translate("GOLD_PICKAXE"));
        assertEquals("GOLDEN_HOE", LegacyMaterialNames.translate("GOLD_HOE"));
        assertEquals("GOLDEN_HELMET", LegacyMaterialNames.translate("GOLD_HELMET"));
        assertEquals("GOLDEN_CHESTPLATE", LegacyMaterialNames.translate("GOLD_CHESTPLATE"));
        assertEquals("GOLDEN_LEGGINGS", LegacyMaterialNames.translate("GOLD_LEGGINGS"));
        assertEquals("GOLDEN_BOOTS", LegacyMaterialNames.translate("GOLD_BOOTS"));
    }

    @Test
    void spadeBecomesShovel() {
        assertEquals("STONE_SHOVEL", LegacyMaterialNames.translate("STONE_SPADE"));
        assertEquals("IRON_SHOVEL", LegacyMaterialNames.translate("IRON_SPADE"));
        assertEquals("DIAMOND_SHOVEL", LegacyMaterialNames.translate("DIAMOND_SPADE"));
    }

    /** The compound cases carry BOTH renames (prefix + SPADE→SHOVEL) — the trap. */
    @Test
    void compoundSpadeCasesCarryBothRenames() {
        assertEquals("WOODEN_SHOVEL", LegacyMaterialNames.translate("WOOD_SPADE"));
        assertEquals("GOLDEN_SHOVEL", LegacyMaterialNames.translate("GOLD_SPADE"));
    }

    /* ---- passthrough (already-modern, and names outside the vocabulary) ---- */

    @Test
    void alreadyModernNamesPassThroughUnchanged() {
        assertEquals("WOODEN_SWORD", LegacyMaterialNames.translate("WOODEN_SWORD"));
        assertEquals("DIAMOND_SWORD", LegacyMaterialNames.translate("DIAMOND_SWORD"));
        assertEquals("IRON_AXE", LegacyMaterialNames.translate("IRON_AXE"));
        assertEquals("NETHERITE_SWORD", LegacyMaterialNames.translate("NETHERITE_SWORD"));
    }

    /** Block names (the GroundFriction feed) are NOT combat vocabulary — untouched. */
    @Test
    void blockAndNonToolNamesAreLeftAlone() {
        assertEquals("SLIME_BLOCK", LegacyMaterialNames.translate("SLIME_BLOCK"));
        assertEquals("PACKED_ICE", LegacyMaterialNames.translate("PACKED_ICE"));
        assertEquals("STONE", LegacyMaterialNames.translate("STONE"));
        assertEquals("AIR", LegacyMaterialNames.translate("AIR"));
    }

    /** On a flattened server (this JVM) modernize is a strict identity — pinned. */
    @Test
    void modernizeIsIdentityOnAFlattenedServer() {
        assertEquals("WOOD_SWORD", LegacyMaterialNames.modernize("WOOD_SWORD"));
        assertEquals("WOODEN_SWORD", LegacyMaterialNames.modernize("WOODEN_SWORD"));
    }

    /* ---- end-to-end: legacy name → era weapon damage pin (Q1) ---- */

    @Test
    void translatedLegacyNameKeysTheEraWeaponTable() {
        // Pins from DamageTablesTest — not re-derived here.
        assertEquals(DamageTables.weaponDamage("WOODEN_SWORD"),
                DamageTables.weaponDamage(LegacyMaterialNames.translate("WOOD_SWORD")));
        assertEquals(5.0, DamageTables.weaponDamage(LegacyMaterialNames.translate("WOOD_SWORD")));
        assertEquals(5.0, DamageTables.weaponDamage(LegacyMaterialNames.translate("GOLD_SWORD")));
        // The compound SPADE→SHOVEL case must reach the shovel row (2.0), not null.
        assertEquals(2.0, DamageTables.weaponDamage(LegacyMaterialNames.translate("WOOD_SPADE")));
        assertEquals(5.0, DamageTables.weaponDamage(LegacyMaterialNames.translate("DIAMOND_SPADE")));
    }
}
