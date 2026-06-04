package me.vexmc.mental.module.hitreg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class DamageCalculatorTest {

    @Test
    void sharpnessFollowsPreNineVanilla() {
        assertEquals(0.0, DamageCalculator.sharpnessBonus(0));
        assertEquals(0.0, DamageCalculator.sharpnessBonus(-3));
        assertEquals(1.25, DamageCalculator.sharpnessBonus(1));
        assertEquals(2.5, DamageCalculator.sharpnessBonus(2));
        assertEquals(3.75, DamageCalculator.sharpnessBonus(3));
        assertEquals(6.25, DamageCalculator.sharpnessBonus(5));
    }

    @Test
    void legacySwordDamageIsFourPlusTier() {
        assertEquals(5.0, DamageCalculator.legacyAttackDamage(Material.WOODEN_SWORD));
        assertEquals(5.0, DamageCalculator.legacyAttackDamage(Material.GOLDEN_SWORD));
        assertEquals(6.0, DamageCalculator.legacyAttackDamage(Material.STONE_SWORD));
        assertEquals(7.0, DamageCalculator.legacyAttackDamage(Material.IRON_SWORD));
        assertEquals(8.0, DamageCalculator.legacyAttackDamage(Material.DIAMOND_SWORD));
        assertEquals(9.0, DamageCalculator.legacyAttackDamage(Material.NETHERITE_SWORD));
    }

    @Test
    void legacyAxeDamageIsThreePlusTierNotAHeavyWeapon() {
        assertEquals(4.0, DamageCalculator.legacyAttackDamage(Material.WOODEN_AXE));
        assertEquals(5.0, DamageCalculator.legacyAttackDamage(Material.STONE_AXE));
        assertEquals(6.0, DamageCalculator.legacyAttackDamage(Material.IRON_AXE));
        assertEquals(7.0, DamageCalculator.legacyAttackDamage(Material.DIAMOND_AXE));
    }

    @Test
    void legacyPickaxeAndShovelTablesHold() {
        assertEquals(3.0, DamageCalculator.legacyAttackDamage(Material.WOODEN_PICKAXE));
        assertEquals(6.0, DamageCalculator.legacyAttackDamage(Material.DIAMOND_PICKAXE));
        assertEquals(2.0, DamageCalculator.legacyAttackDamage(Material.WOODEN_SHOVEL));
        assertEquals(5.0, DamageCalculator.legacyAttackDamage(Material.DIAMOND_SHOVEL));
    }

    @Test
    void nonToolsFallThroughToTheAttribute() {
        assertNull(DamageCalculator.legacyAttackDamage(Material.AIR));
        assertNull(DamageCalculator.legacyAttackDamage(Material.STICK));
        assertNull(DamageCalculator.legacyAttackDamage(Material.WOODEN_HOE));
        assertNull(DamageCalculator.legacyAttackDamage(Material.FISHING_ROD));
    }
}
