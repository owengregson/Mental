package me.vexmc.mental.kernel.math;

import static org.junit.jupiter.api.Assertions.assertEquals;

import me.vexmc.mental.kernel.math.Ct8cTables.Tier;
import me.vexmc.mental.kernel.math.Ct8cTables.WeaponClass;
import org.junit.jupiter.api.Test;

/**
 * The Combat Test 8c weapon tables — attack speed, attack delay, reach and
 * damage — hand-computed from spec §2.2 (which is itself code-confirmed against
 * the decompiled {@code 1_16_combat-6} client: {@code WeaponType}, {@code
 * Attributes}, {@code Tiers}). Every number here is the spec table verbatim; a
 * drift is a departure from the snapshot, not a rounding choice.
 */
class Ct8cTablesTest {

    private static final double EPSILON = 1.0e-9;

    /* ------------------------------- attack speed (attribute value = att/s + 1.5) ------- */

    @Test
    void attackSpeedIsTheAttributeValueNotTheAttacksPerSecond() {
        // Spec §2.2 parenthesised column: fist 2.5 (4.0), sword 3.0 (4.5),
        // axe 2.0 (3.5), pickaxe 2.5 (4.0), shovel 2.0 (3.5), trident 2.0 (3.5).
        assertEquals(4.0, Ct8cTables.attackSpeed(WeaponClass.FIST, Tier.NONE), EPSILON);
        assertEquals(4.5, Ct8cTables.attackSpeed(WeaponClass.SWORD, Tier.IRON), EPSILON);
        assertEquals(3.5, Ct8cTables.attackSpeed(WeaponClass.AXE, Tier.DIAMOND), EPSILON);
        assertEquals(4.0, Ct8cTables.attackSpeed(WeaponClass.PICKAXE, Tier.STONE), EPSILON);
        assertEquals(3.5, Ct8cTables.attackSpeed(WeaponClass.SHOVEL, Tier.NETHERITE), EPSILON);
        assertEquals(3.5, Ct8cTables.attackSpeed(WeaponClass.TRIDENT, Tier.NONE), EPSILON);
    }

    @Test
    void hoeAttackSpeedClimbsWithTier() {
        // Spec §2.2 hoe att/s 2.0/2.5/3.0/3.5/3.5/3.5 → attr 3.5/4.0/4.5/5.0/5.0/5.0.
        assertEquals(3.5, Ct8cTables.attackSpeed(WeaponClass.HOE, Tier.WOOD), EPSILON);
        assertEquals(4.0, Ct8cTables.attackSpeed(WeaponClass.HOE, Tier.STONE), EPSILON);
        assertEquals(4.5, Ct8cTables.attackSpeed(WeaponClass.HOE, Tier.IRON), EPSILON);
        assertEquals(5.0, Ct8cTables.attackSpeed(WeaponClass.HOE, Tier.GOLD), EPSILON);
        assertEquals(5.0, Ct8cTables.attackSpeed(WeaponClass.HOE, Tier.DIAMOND), EPSILON);
        assertEquals(5.0, Ct8cTables.attackSpeed(WeaponClass.HOE, Tier.NETHERITE), EPSILON);
    }

    /* ------------------------------- attack delay -------------------------------------- */

    @Test
    void attackDelayIsTheClampedInverseRoundedDown() {
        // (int)(20 / clamp(s − 1.5, 0.1, 1024) + 0.5): the spec §2.1 formula.
        assertEquals(7, Ct8cTables.attackDelayTicks(4.5)); // 20/3.0 + 0.5 = 7.166…
        assertEquals(10, Ct8cTables.attackDelayTicks(3.5)); // 20/2.0 + 0.5 = 10.5
        assertEquals(8, Ct8cTables.attackDelayTicks(4.0)); // 20/2.5 + 0.5 = 8.5
        assertEquals(6, Ct8cTables.attackDelayTicks(5.0)); // 20/3.5 + 0.5 = 6.214…
    }

    @Test
    void attackDelayMatchesTheSpecDelayColumnForEveryWeapon() {
        // Spec §2.2 delay column, derived by feeding the attribute value through
        // attackDelayTicks — the two tables must agree.
        assertEquals(8, Ct8cTables.attackDelayTicks(Ct8cTables.attackSpeed(WeaponClass.FIST, Tier.NONE)));
        assertEquals(7, Ct8cTables.attackDelayTicks(Ct8cTables.attackSpeed(WeaponClass.SWORD, Tier.IRON)));
        assertEquals(10, Ct8cTables.attackDelayTicks(Ct8cTables.attackSpeed(WeaponClass.AXE, Tier.IRON)));
        assertEquals(8, Ct8cTables.attackDelayTicks(Ct8cTables.attackSpeed(WeaponClass.PICKAXE, Tier.IRON)));
        assertEquals(10, Ct8cTables.attackDelayTicks(Ct8cTables.attackSpeed(WeaponClass.SHOVEL, Tier.IRON)));
        assertEquals(10, Ct8cTables.attackDelayTicks(Ct8cTables.attackSpeed(WeaponClass.TRIDENT, Tier.NONE)));
        // Hoe delay 10/8/7/6/6/6 across the tiers.
        assertEquals(10, Ct8cTables.attackDelayTicks(Ct8cTables.attackSpeed(WeaponClass.HOE, Tier.WOOD)));
        assertEquals(8, Ct8cTables.attackDelayTicks(Ct8cTables.attackSpeed(WeaponClass.HOE, Tier.STONE)));
        assertEquals(7, Ct8cTables.attackDelayTicks(Ct8cTables.attackSpeed(WeaponClass.HOE, Tier.IRON)));
        assertEquals(6, Ct8cTables.attackDelayTicks(Ct8cTables.attackSpeed(WeaponClass.HOE, Tier.GOLD)));
        assertEquals(6, Ct8cTables.attackDelayTicks(Ct8cTables.attackSpeed(WeaponClass.HOE, Tier.NETHERITE)));
    }

    /* ------------------------------- reach --------------------------------------------- */

    @Test
    void reachIsTwoAndAHalfExceptSwordThreeAndHoeTridentThreeAndAHalf() {
        assertEquals(2.5, Ct8cTables.reach(WeaponClass.FIST), EPSILON);
        assertEquals(3.0, Ct8cTables.reach(WeaponClass.SWORD), EPSILON);
        assertEquals(2.5, Ct8cTables.reach(WeaponClass.AXE), EPSILON);
        assertEquals(2.5, Ct8cTables.reach(WeaponClass.PICKAXE), EPSILON);
        assertEquals(2.5, Ct8cTables.reach(WeaponClass.SHOVEL), EPSILON);
        assertEquals(3.5, Ct8cTables.reach(WeaponClass.HOE), EPSILON);
        assertEquals(3.5, Ct8cTables.reach(WeaponClass.TRIDENT), EPSILON);
    }

    /* ------------------------------- damage -------------------------------------------- */

    @Test
    void handComputedDamagePins() {
        // Plan pins: spec §2.2 composition (base 2 + weapon bonus + tier bonus).
        assertEquals(7.0, Ct8cTables.damage(WeaponClass.SWORD, Tier.NETHERITE), EPSILON);
        assertEquals(3.0, Ct8cTables.damage(WeaponClass.HOE, Tier.DIAMOND), EPSILON);
        assertEquals(2.0, Ct8cTables.damage(WeaponClass.FIST, Tier.NONE), EPSILON);
    }

    @Test
    void swordDamageTableWoodStoneIronGoldDiamondNetherite() {
        assertEquals(4.0, Ct8cTables.damage(WeaponClass.SWORD, Tier.WOOD), EPSILON);
        assertEquals(4.0, Ct8cTables.damage(WeaponClass.SWORD, Tier.STONE), EPSILON);
        assertEquals(5.0, Ct8cTables.damage(WeaponClass.SWORD, Tier.IRON), EPSILON);
        assertEquals(4.0, Ct8cTables.damage(WeaponClass.SWORD, Tier.GOLD), EPSILON);
        assertEquals(6.0, Ct8cTables.damage(WeaponClass.SWORD, Tier.DIAMOND), EPSILON);
        assertEquals(7.0, Ct8cTables.damage(WeaponClass.SWORD, Tier.NETHERITE), EPSILON);
    }

    @Test
    void axeDamageTable() {
        assertEquals(5.0, Ct8cTables.damage(WeaponClass.AXE, Tier.WOOD), EPSILON);
        assertEquals(5.0, Ct8cTables.damage(WeaponClass.AXE, Tier.STONE), EPSILON);
        assertEquals(6.0, Ct8cTables.damage(WeaponClass.AXE, Tier.IRON), EPSILON);
        assertEquals(5.0, Ct8cTables.damage(WeaponClass.AXE, Tier.GOLD), EPSILON);
        assertEquals(7.0, Ct8cTables.damage(WeaponClass.AXE, Tier.DIAMOND), EPSILON);
        assertEquals(8.0, Ct8cTables.damage(WeaponClass.AXE, Tier.NETHERITE), EPSILON);
    }

    @Test
    void pickaxeDamageTable() {
        assertEquals(3.0, Ct8cTables.damage(WeaponClass.PICKAXE, Tier.WOOD), EPSILON);
        assertEquals(4.0, Ct8cTables.damage(WeaponClass.PICKAXE, Tier.IRON), EPSILON);
        assertEquals(5.0, Ct8cTables.damage(WeaponClass.PICKAXE, Tier.DIAMOND), EPSILON);
        assertEquals(6.0, Ct8cTables.damage(WeaponClass.PICKAXE, Tier.NETHERITE), EPSILON);
    }

    @Test
    void shovelDamageTable() {
        assertEquals(2.0, Ct8cTables.damage(WeaponClass.SHOVEL, Tier.WOOD), EPSILON);
        assertEquals(3.0, Ct8cTables.damage(WeaponClass.SHOVEL, Tier.IRON), EPSILON);
        assertEquals(4.0, Ct8cTables.damage(WeaponClass.SHOVEL, Tier.DIAMOND), EPSILON);
        assertEquals(5.0, Ct8cTables.damage(WeaponClass.SHOVEL, Tier.NETHERITE), EPSILON);
    }

    @Test
    void hoeDamageIgnoresTierBonusAndUsesFlatIronDiamondNetheriteAdditions() {
        // Spec §2.2: hoe ignores the tier bonus, flat +1.0 iron/diamond, +2.0 netherite.
        assertEquals(2.0, Ct8cTables.damage(WeaponClass.HOE, Tier.WOOD), EPSILON);
        assertEquals(2.0, Ct8cTables.damage(WeaponClass.HOE, Tier.STONE), EPSILON);
        assertEquals(3.0, Ct8cTables.damage(WeaponClass.HOE, Tier.IRON), EPSILON);
        assertEquals(2.0, Ct8cTables.damage(WeaponClass.HOE, Tier.GOLD), EPSILON);
        assertEquals(3.0, Ct8cTables.damage(WeaponClass.HOE, Tier.DIAMOND), EPSILON);
        assertEquals(4.0, Ct8cTables.damage(WeaponClass.HOE, Tier.NETHERITE), EPSILON);
    }

    @Test
    void tridentDamageIsAFlatSevenRegardlessOfTier() {
        // Spec §2.2: trident flat +5.0, tier-independent.
        assertEquals(7.0, Ct8cTables.damage(WeaponClass.TRIDENT, Tier.NONE), EPSILON);
        assertEquals(7.0, Ct8cTables.damage(WeaponClass.TRIDENT, Tier.DIAMOND), EPSILON);
    }
}
