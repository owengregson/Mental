package me.vexmc.mental.kernel.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins {@link ArmourDurabilityMath} to OCM's exact integer arithmetic.
 *
 * <p>OCM's {@code ModuleOldArmourDurability} computes
 * {@code damageChance = 60 + 40/(level+1)} and KEEPS the (vanilla) durability
 * damage when {@code roll < damageChance}, SKIPPING it (setDamage 0) when
 * {@code roll >= damageChance}. So {@code damageChance} is the percent chance the
 * piece DOES take wear; the skip chance is its complement. Crucially, at
 * unbreaking level 0 {@code damageChance == 100}, so a 0..99 roll is never
 * {@code >= 100} → level-0 armour NEVER skips (always wears). The "armour lasts
 * longer" feel comes from era keeping vanilla's small {@code max(1,floor(d/4))}
 * magnitude, plus the rising skip chance as Unbreaking increases — not from a
 * level-0 base skip. These pins are read straight off OCM's source.</p>
 */
class ArmourDurabilityMathTest {

    @Test
    void damageChancePercentMatchesOcmIntegerMath() {
        // 60 + 40/(level+1), integer division — OCM's exact expression.
        assertEquals(100, ArmourDurabilityMath.damageChancePercent(0)); // 60 + 40/1
        assertEquals(80, ArmourDurabilityMath.damageChancePercent(1)); // 60 + 40/2
        assertEquals(73, ArmourDurabilityMath.damageChancePercent(2)); // 60 + 40/3 = 60+13
        assertEquals(70, ArmourDurabilityMath.damageChancePercent(3)); // 60 + 40/4
        assertEquals(68, ArmourDurabilityMath.damageChancePercent(4)); // 60 + 40/5
    }

    @Test
    void levelZeroNeverSkips() {
        // damageChance == 100; no 0..99 roll is >= 100, so wear always applies.
        assertFalse(ArmourDurabilityMath.skipsDamage(0, 0));
        assertFalse(ArmourDurabilityMath.skipsDamage(0, 50));
        assertFalse(ArmourDurabilityMath.skipsDamage(0, 99));
    }

    @Test
    void skipBoundaryIsRollGreaterOrEqualToDamageChance() {
        // Level 1: damageChance 80 → skip iff roll >= 80 (a 20% skip window).
        assertFalse(ArmourDurabilityMath.skipsDamage(1, 79));
        assertTrue(ArmourDurabilityMath.skipsDamage(1, 80));
        assertTrue(ArmourDurabilityMath.skipsDamage(1, 99));

        // Level 3: damageChance 70 → skip iff roll >= 70 (a 30% skip window).
        assertFalse(ArmourDurabilityMath.skipsDamage(3, 69));
        assertTrue(ArmourDurabilityMath.skipsDamage(3, 70));
    }

    @Test
    void higherUnbreakingSkipsMoreOften() {
        // Skip window widens with level: 0% (L0) < 20% (L1) < 30% (L3).
        int skipsL0 = countSkips(0);
        int skipsL1 = countSkips(1);
        int skipsL3 = countSkips(3);
        assertEquals(0, skipsL0);
        assertEquals(20, skipsL1);
        assertEquals(30, skipsL3);
        assertTrue(skipsL0 < skipsL1 && skipsL1 < skipsL3);
    }

    /** Counts, over every possible roll 0..99, how many SKIP at this level. */
    private static int countSkips(int level) {
        int count = 0;
        for (int roll = 0; roll < 100; roll++) {
            if (ArmourDurabilityMath.skipsDamage(level, roll)) {
                count++;
            }
        }
        return count;
    }
}
