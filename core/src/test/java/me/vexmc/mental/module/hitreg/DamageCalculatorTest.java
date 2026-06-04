package me.vexmc.mental.module.hitreg;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DamageCalculatorTest {

    @Test
    void sharpnessFollowsVanillaOneEight() {
        assertEquals(0.0, DamageCalculator.sharpnessBonus(0));
        assertEquals(0.0, DamageCalculator.sharpnessBonus(-3));
        assertEquals(1.0, DamageCalculator.sharpnessBonus(1));
        assertEquals(1.5, DamageCalculator.sharpnessBonus(2));
        assertEquals(2.0, DamageCalculator.sharpnessBonus(3));
        assertEquals(3.0, DamageCalculator.sharpnessBonus(5));
    }
}
