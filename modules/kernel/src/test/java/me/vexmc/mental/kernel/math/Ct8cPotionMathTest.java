package me.vexmc.mental.kernel.math;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The Combat Test 8c potion values (spec §2.8, code-confirmed against {@code
 * MobEffects}/{@code AttackDamageMobEffect}): Instant Health/Damage heal or hit
 * {@code 6·2^amp}, Strength/Weakness are ±20%/level MULTIPLY_TOTAL attack-damage
 * modifiers, and tipped-arrow instantaneous effects are scaled ×1/8.
 */
class Ct8cPotionMathTest {

    private static final double EPSILON = 1.0e-9;

    @Test
    void instantHealthIsSixTimesTwoToTheAmplifier() {
        assertEquals(6, Ct8cPotionMath.instantHealth(0)); // Instant Health I
        assertEquals(12, Ct8cPotionMath.instantHealth(1)); // Instant Health II
        assertEquals(24, Ct8cPotionMath.instantHealth(2));
    }

    @Test
    void instantDamageMirrorsInstantHealth() {
        assertEquals(6, Ct8cPotionMath.instantDamage(0));
        assertEquals(12, Ct8cPotionMath.instantDamage(1));
        assertEquals(24, Ct8cPotionMath.instantDamage(2));
    }

    @Test
    void strengthIsPlusTwentyPercentPerLevel() {
        assertEquals(0.2, Ct8cPotionMath.strengthMultiplier(0), EPSILON); // Strength I
        assertEquals(0.4, Ct8cPotionMath.strengthMultiplier(1), EPSILON); // Strength II
    }

    @Test
    void weaknessIsMinusTwentyPercentPerLevel() {
        assertEquals(-0.2, Ct8cPotionMath.weaknessMultiplier(0), EPSILON);
        assertEquals(-0.4, Ct8cPotionMath.weaknessMultiplier(1), EPSILON);
    }

    @Test
    void tippedArrowScaleIsOneEighth() {
        assertEquals(0.125, Ct8cPotionMath.tippedArrowScale(), EPSILON);
    }
}
