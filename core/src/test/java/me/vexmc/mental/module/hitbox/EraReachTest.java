package me.vexmc.mental.module.hitbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins {@link EraReach} to the 1.7/1.8 melee reach + hitbox geometry.
 *
 * <p>Era truth (combat compendium / OCM AttackRange defaults): the survival melee
 * window is {@code [0, 3.0]} blocks (eye-to-AABB), creative {@code [0, 4.0]}, with
 * a {@code 0.1} hitbox grow margin and a {@code 1.0} mob factor. These are the
 * exact constants written into both the {@code ATTACK_RANGE} item component
 * (1.21.5+) and the {@code ENTITY_INTERACTION_RANGE} attribute base (1.20.5+);
 * the attribute clamp keeps a hand-set value inside a sane melee band.</p>
 */
class EraReachTest {

    @Test
    void survivalReachIsThreeBlocks() {
        assertEquals(3.0, EraReach.MAX_REACH);
        assertEquals(0.0, EraReach.MIN_REACH);
    }

    @Test
    void creativeReachIsFourBlocks() {
        assertEquals(4.0, EraReach.MAX_CREATIVE_REACH);
        assertEquals(0.0, EraReach.MIN_CREATIVE_REACH);
    }

    @Test
    void hitboxMarginAndMobFactorMatchEra() {
        assertEquals(0.1, EraReach.HITBOX_MARGIN);
        assertEquals(1.0, EraReach.MOB_FACTOR);
    }

    @Test
    void interactionRangeClampLeavesEraValueUntouched() {
        // The era value sits inside the band, so it passes through unchanged.
        assertEquals(3.0, EraReach.clampInteractionRange(3.0));
    }

    @Test
    void interactionRangeClampPinsAnInflatedValueToTheCeiling() {
        // A third-party inflation (e.g. 6.0) is brought back into the melee band.
        assertEquals(EraReach.MAX_INTERACTION_RANGE, EraReach.clampInteractionRange(6.0));
    }

    @Test
    void interactionRangeClampPinsANegativeValueToZero() {
        assertEquals(0.0, EraReach.clampInteractionRange(-1.0));
    }
}
