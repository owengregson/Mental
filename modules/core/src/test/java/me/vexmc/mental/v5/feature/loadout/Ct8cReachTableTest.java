package me.vexmc.mental.v5.feature.loadout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pins the CT8c reach table and the below-floor degrade decision (spec §2.2). */
class Ct8cReachTableTest {

    @Test
    void reachPerWeaponClass() {
        assertEquals(2.5, Ct8cReachTable.reachFor("AIR"), 1e-9);          // fist
        assertEquals(2.5, Ct8cReachTable.reachFor("DIAMOND_AXE"), 1e-9);
        assertEquals(2.5, Ct8cReachTable.reachFor("IRON_PICKAXE"), 1e-9);
        assertEquals(2.5, Ct8cReachTable.reachFor("STONE_SHOVEL"), 1e-9);
        assertEquals(3.0, Ct8cReachTable.reachFor("NETHERITE_SWORD"), 1e-9);
        assertEquals(3.5, Ct8cReachTable.reachFor("DIAMOND_HOE"), 1e-9);
        assertEquals(3.5, Ct8cReachTable.reachFor("TRIDENT"), 1e-9);
    }

    @Test
    void onlyThreeAndAHalfReachIsImpossibleBelowFloor() {
        assertTrue(Ct8cReachTable.impossibleBelowFloor(3.5), "hoe/trident 3.5 cannot be enforced sub-floor");
        assertFalse(Ct8cReachTable.impossibleBelowFloor(3.0), "sword 3.0 is at the sub-floor ceiling");
        assertFalse(Ct8cReachTable.impossibleBelowFloor(2.5), "base 2.5 is under the ceiling");
    }
}
