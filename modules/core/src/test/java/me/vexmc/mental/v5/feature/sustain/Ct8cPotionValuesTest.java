package me.vexmc.mental.v5.feature.sustain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins the CT8c Strength/Weakness melee factors (spec §2.8): {@code ±20%·level}
 * MULTIPLY_TOTAL — Strength I {@code ×1.2} / II {@code ×1.4}, Weakness I
 * {@code ×0.8} / II {@code ×0.6}, composed multiplicatively onto a weapon base.
 */
class Ct8cPotionValuesTest {

    private static final double EPSILON = 1.0e-9;

    @Test
    void strengthIsPlusTwentyPercentPerLevel() {
        assertEquals(1.2, Ct8cPotionValues.strengthFactor(0), EPSILON); // Strength I: +20%
        assertEquals(1.4, Ct8cPotionValues.strengthFactor(1), EPSILON); // Strength II: +40%
    }

    @Test
    void weaknessIsMinusTwentyPercentPerLevel() {
        assertEquals(0.8, Ct8cPotionValues.weaknessFactor(0), EPSILON); // Weakness I: −20%
        assertEquals(0.6, Ct8cPotionValues.weaknessFactor(1), EPSILON); // Weakness II: −40%
    }

    @Test
    void appliesEachEffectOnlyWhenPresent() {
        // amp −1 = absent (DamageShaper convention); a bare base is untouched.
        assertEquals(10.0, Ct8cPotionValues.apply(10.0, -1, -1), EPSILON);
        assertEquals(12.0, Ct8cPotionValues.apply(10.0, 0, -1), EPSILON);  // Strength I only
        assertEquals(14.0, Ct8cPotionValues.apply(10.0, 1, -1), EPSILON);  // Strength II only
        assertEquals(8.0, Ct8cPotionValues.apply(10.0, -1, 0), EPSILON);   // Weakness I only
    }

    @Test
    void strengthAndWeaknessComposeMultiplicatively() {
        // Both present: 10 · 1.2 · 0.8 = 9.6 (MULTIPLY_TOTAL stacks, not additively).
        assertEquals(9.6, Ct8cPotionValues.apply(10.0, 0, 0), EPSILON);
    }

    @Test
    void clampsAtZero() {
        // A base of 0 stays 0; the factors can never drive a hit negative.
        assertEquals(0.0, Ct8cPotionValues.apply(0.0, 0, 1), EPSILON);
    }
}
