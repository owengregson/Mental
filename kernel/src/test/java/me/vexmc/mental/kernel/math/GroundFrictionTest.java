package me.vexmc.mental.kernel.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the era slipperiness table exactly as ported from core — the values
 * are era constants (compendium §5b), so any drift here is a physics bug.
 */
class GroundFrictionTest {

    @Test
    void iceFamilySlipsAtEraValue() {
        assertEquals(0.98, GroundFriction.of("ICE"));
        assertEquals(0.98, GroundFriction.of("PACKED_ICE"));
        assertEquals(0.98, GroundFriction.of("FROSTED_ICE"));
    }

    @Test
    void blueIceUsesItsRealModernSlipperiness() {
        assertEquals(0.989, GroundFriction.of("BLUE_ICE"));
    }

    @Test
    void slimeSlipsAtPointEight() {
        assertEquals(0.8, GroundFriction.of("SLIME_BLOCK"));
    }

    @Test
    void everythingElseDefaultsToPointSix() {
        assertEquals(0.6, GroundFriction.of("STONE"));
        assertEquals(0.6, GroundFriction.of("GRASS_BLOCK"));
        assertEquals(0.6, GroundFriction.of("NOT_A_REAL_BLOCK"));
    }
}
