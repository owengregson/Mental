package me.vexmc.mental.kernel.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The Combat Test 8c food/regen gates (spec §2.7, the 1.8-style {@code
 * FoodData} model): natural regen and starvation both tick every 40 ticks, and
 * both regen and sprinting require {@code foodLevel > 6}.
 */
class Ct8cRegenMathTest {

    @Test
    void regenAndStarveIntervalsAreBothFortyTicks() {
        assertEquals(40, Ct8cRegenMath.REGEN_INTERVAL_TICKS);
        assertEquals(40, Ct8cRegenMath.STARVE_INTERVAL_TICKS);
    }

    @Test
    void regenNeedsFoodStrictlyAboveSix() {
        assertTrue(Ct8cRegenMath.canRegen(7));
        assertFalse(Ct8cRegenMath.canRegen(6), "the gate is strictly greater than 6");
        assertFalse(Ct8cRegenMath.canRegen(0));
    }

    @Test
    void sprintNeedsFoodStrictlyAboveSix() {
        assertTrue(Ct8cRegenMath.canSprint(7));
        assertFalse(Ct8cRegenMath.canSprint(6));
        assertFalse(Ct8cRegenMath.canSprint(0));
    }
}
