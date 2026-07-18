package me.vexmc.mental.kernel.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the 1.8 regen gate logic and era constants from the decompiled
 * FoodStats (xg.java, decomp-1.8.9).
 */
class RegenMathTest {

    /* ------------------------------------------------------------------ */
    /*  Constant pins — drift here means the engine diverged from era      */
    /* ------------------------------------------------------------------ */

    @Test
    void intervalTicksIs80() {
        assertEquals(80L, RegenMath.INTERVAL_TICKS,
                "FoodStats: if (++foodTickTimer >= 80)");
    }

    @Test
    void healAmountIsOneHp() {
        assertEquals(1.0, RegenMath.HEAL_AMOUNT,
                "FoodStats: heal(1.0f) — half a heart per cycle");
    }

    @Test
    void exhaustionPerHealIsThree() {
        assertEquals(3.0f, RegenMath.EXHAUSTION_PER_HEAL,
                "FoodStats: addExhaustion(3.0f)");
    }

    @Test
    void foodGateIs18() {
        assertEquals(18, RegenMath.FOOD_GATE,
                "FoodStats: if (foodLevel >= 18)");
    }

    /* ------------------------------------------------------------------ */
    /*  Gate logic                                                         */
    /* ------------------------------------------------------------------ */

    @Test
    void healsWhenFedAndHurtAndGameruleOn() {
        // foodLevel == 18 (gate minimum), health < maxHealth, gamerule on
        assertTrue(RegenMath.shouldHeal(18, 10.0, 20.0, true),
                "should heal when at gate minimum food, hurt, gamerule on");
    }

    @Test
    void healsWhenFoodAboveGate() {
        assertTrue(RegenMath.shouldHeal(20, 10.0, 20.0, true),
                "full hunger bar should also trigger regen");
    }

    @Test
    void noHealWhenFoodBelowGate() {
        assertFalse(RegenMath.shouldHeal(17, 10.0, 20.0, true),
                "foodLevel 17 is below the gate of 18");
    }

    @Test
    void noHealAtFoodZero() {
        assertFalse(RegenMath.shouldHeal(0, 10.0, 20.0, true),
                "starving player must not trigger regen");
    }

    @Test
    void noHealAtFullHealth() {
        assertFalse(RegenMath.shouldHeal(20, 20.0, 20.0, true),
                "already at max health — nothing to heal");
    }

    @Test
    void noHealAtZeroHealth() {
        // health == 0 means dead; the era gate requires health > 0
        assertFalse(RegenMath.shouldHeal(20, 0.0, 20.0, true),
                "dead player (health == 0) must not regen");
    }

    @Test
    void noHealBelowZeroHealth() {
        assertFalse(RegenMath.shouldHeal(20, -1.0, 20.0, true),
                "negative health must not regen");
    }

    @Test
    void noHealWhenNaturalRegenGameruleOff() {
        assertFalse(RegenMath.shouldHeal(20, 10.0, 20.0, false),
                "naturalRegeneration gamerule off must suppress regen");
    }

    @Test
    void noHealWhenBothGameruleOffAndFoodLow() {
        assertFalse(RegenMath.shouldHeal(10, 10.0, 20.0, false),
                "multiple blocking conditions — still no heal");
    }

    @Test
    void healAtExactlyOneHpBelowMax() {
        // A player who is 1 HP below max should still get the regen tick
        assertTrue(RegenMath.shouldHeal(18, 19.0, 20.0, true),
                "exactly one HP below max should heal");
    }
}
