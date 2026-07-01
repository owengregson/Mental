package me.vexmc.mental.kernel.math;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins {@link SwordBlockReduction} to the 1.8 blocking model.
 *
 * <p>Era truth ({@code ModuleShieldDamageReduction f = (1.0F + f) * 0.5F};
 * compendium wire-measured {@code 4.5 = (1 + 8) * 0.5}): a blocked melee hit
 * deals {@code (1 + damage) * 0.5} taken — equivalently a reduction of
 * {@code (damage - 1) * 0.5}, clamped at 0 so a sub-1 hit is never amplified.
 * The native 1.21.5+ {@code BlocksAttacks.DamageReduction.resolve} computes the
 * same value as {@code clamp(base + factor*damage)} with {@code base=-0.5,
 * factor=0.5}; this software path produces the byte-identical reduction for the
 * Tier-B (consumable-only) versions where the component does not reduce.</p>
 */
class SwordBlockReductionTest {

    @Test
    void eightDamageBlocksToThreePointFive() {
        // (8 - 1) * 0.5 = 3.5 reduction.
        assertEquals(3.5, SwordBlockReduction.blockedDamage(8.0));
    }

    @Test
    void oneDamageBlocksToZero() {
        // (1 - 1) * 0.5 = 0 reduction.
        assertEquals(0.0, SwordBlockReduction.blockedDamage(1.0));
    }

    @Test
    void tenDamageBlocksToFourPointFive() {
        // The wire-measured era value: (10 - 1) * 0.5 = 4.5 reduction.
        assertEquals(4.5, SwordBlockReduction.blockedDamage(10.0));
    }

    @Test
    void zeroDamageReducesToZeroNotNegative() {
        // (0 - 1) * 0.5 = -0.5 raw, clamped to 0 — a block never amplifies.
        assertEquals(0.0, SwordBlockReduction.blockedDamage(0.0));
    }
}
