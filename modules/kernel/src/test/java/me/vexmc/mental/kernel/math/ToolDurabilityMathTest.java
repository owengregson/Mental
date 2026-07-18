package me.vexmc.mental.kernel.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins {@link ToolDurabilityMath} to the era weapon Unbreaking skip — the
 * generic (non-armour) tool/weapon path, NOT the armour-specific 0.6 branch.
 *
 * <p>1.8.9 {@code EnchantmentDurability.a(stack, level, random)}
 * ({@code acg.java:36-41}): the armour branch ({@code random.nextFloat() < 0.6}
 * → skip) does not apply to weapons, so a sword falls through to
 * {@code return random.nextInt(level + 1) > 0;}. That expression returns the
 * NEGATION (skip) verdict: it is {@code true} (skip the damage) when
 * {@code nextInt(level+1) > 0}, so the item DAMAGES only on the single outcome
 * {@code nextInt(level+1) == 0} — probability {@code 1/(level+1)}. At level 0,
 * {@code nextInt(1)} is always 0, so an unenchanted weapon always damages. This
 * is unchanged 1.7→modern for the attack case (1 durability per entity hit).</p>
 */
class ToolDurabilityMathTest {

    @Test
    void levelZeroAlwaysDamages() {
        // nextInt(1) is always 0 → the single damaging outcome is certain.
        assertTrue(ToolDurabilityMath.damagesThisHit(0, 0));
    }

    @Test
    void damagesOnlyOnTheZeroRoll() {
        // The item damages iff the roll picks outcome 0 (prob 1/(level+1)); any
        // higher roll skips. Level 1: roll 0 damages, roll 1 skips.
        assertTrue(ToolDurabilityMath.damagesThisHit(1, 0));
        assertFalse(ToolDurabilityMath.damagesThisHit(1, 1));

        // Level 3: roll 0 damages, rolls 1..3 skip (a 1-in-4 damage window).
        assertTrue(ToolDurabilityMath.damagesThisHit(3, 0));
        assertFalse(ToolDurabilityMath.damagesThisHit(3, 1));
        assertFalse(ToolDurabilityMath.damagesThisHit(3, 2));
        assertFalse(ToolDurabilityMath.damagesThisHit(3, 3));
    }

    @Test
    void damageWindowIsOneInLevelPlusOne() {
        // Over every roll in [0, level], exactly one outcome damages.
        assertEquals(1, countDamages(0)); // 1 of 1  → always (level 0)
        assertEquals(1, countDamages(1)); // 1 of 2  → 1/2
        assertEquals(1, countDamages(3)); // 1 of 4  → 1/4
    }

    @Test
    void higherUnbreakingDamagesLessOften() {
        // Damage probability falls as 1/(level+1): 1/1 > 1/2 > 1/4.
        double pL0 = countDamages(0) / 1.0;
        double pL1 = countDamages(1) / 2.0;
        double pL3 = countDamages(3) / 4.0;
        assertTrue(pL0 > pL1 && pL1 > pL3);
        assertEquals(1.0, pL0);
        assertEquals(0.5, pL1);
        assertEquals(0.25, pL3);
    }

    @Test
    void negativeLevelTreatedAsZero() {
        // Defensive: a missing/absent enchant level collapses to "always damage".
        assertTrue(ToolDurabilityMath.damagesThisHit(-1, 0));
    }

    /** Counts, over every roll in {@code [0, level]}, how many DAMAGE at this level. */
    private static int countDamages(int level) {
        int count = 0;
        for (int roll = 0; roll <= level; roll++) {
            if (ToolDurabilityMath.damagesThisHit(level, roll)) {
                count++;
            }
        }
        return count;
    }
}
