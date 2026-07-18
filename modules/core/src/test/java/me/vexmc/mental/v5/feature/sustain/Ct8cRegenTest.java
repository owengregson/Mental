package me.vexmc.mental.v5.feature.sustain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.vexmc.mental.v5.feature.sustain.Ct8cRegenDriver.Outcome;
import org.junit.jupiter.api.Test;

/**
 * Pins the CT8c regen gates and cadence (spec §2.7): heal 1 HP every 40 ticks
 * while {@code foodLevel > 6} and hurt, with a 50% hunger drain per heal. The
 * cadence is exercised against {@link Ct8cRegenDriver} with a fake clock (an
 * explicit tick loop) and a deterministic drain roll.
 */
class Ct8cRegenTest {

    /* ------------------------------- gates -------------------------------- */

    @Test
    void healsOnlyAboveTheFoodGateWhenHurt() {
        assertTrue(Ct8cRegen.heals(7, 5.0, 20.0, true));   // food > 6, hurt, gamerule on
        assertFalse(Ct8cRegen.heals(6, 5.0, 20.0, true));  // food == 6 is NOT above the gate
        assertFalse(Ct8cRegen.heals(20, 20.0, 20.0, true)); // full health — no room
        assertFalse(Ct8cRegen.heals(20, 0.0, 20.0, true));  // dead — no heal
        assertFalse(Ct8cRegen.heals(20, 5.0, 20.0, false)); // naturalRegeneration off
    }

    @Test
    void drainIsAFiftyPercentCoinFlip() {
        assertTrue(Ct8cRegen.drains(0.0));
        assertTrue(Ct8cRegen.drains(0.4999));
        assertFalse(Ct8cRegen.drains(0.5));
        assertFalse(Ct8cRegen.drains(0.9));
    }

    @Test
    void onlyAPlayerOrMobHitInterruptsAConsume() {
        assertTrue(Ct8cRegen.interruptsConsume(true, true));    // eating, hit by a living damager
        assertFalse(Ct8cRegen.interruptsConsume(false, true));  // not eating — nothing to interrupt
        assertFalse(Ct8cRegen.interruptsConsume(true, false));  // eating, but the damager is not living (projectile/block)
        assertFalse(Ct8cRegen.interruptsConsume(false, false));
    }

    /* ------------------------------ cadence ------------------------------- */

    @Test
    void healsExactlyEveryFortyTicks() {
        Ct8cRegenDriver driver = new Ct8cRegenDriver(() -> 0.0); // always drain
        // Ticks 1..39 are mid-interval — no heal.
        for (int tick = 1; tick <= 39; tick++) {
            assertEquals(Outcome.NONE, driver.tick(10, 5.0, 20.0, true), "mid-interval tick " + tick);
        }
        // The 40th tick fires the heal (and the drain, roll 0.0 < 0.5).
        assertEquals(Outcome.HEAL_AND_DRAIN, driver.tick(10, 5.0, 20.0, true));
        // The counter reset — the next boundary is another 40 ticks out.
        for (int tick = 1; tick <= 39; tick++) {
            assertEquals(Outcome.NONE, driver.tick(10, 5.0, 20.0, true), "second-window tick " + tick);
        }
        assertEquals(Outcome.HEAL_AND_DRAIN, driver.tick(10, 5.0, 20.0, true));
    }

    @Test
    void boundaryWithoutDrainStillHeals() {
        Ct8cRegenDriver driver = new Ct8cRegenDriver(() -> 0.9); // never drain
        Outcome outcome = Outcome.NONE;
        for (int tick = 1; tick <= 40; tick++) {
            outcome = driver.tick(10, 5.0, 20.0, true);
        }
        assertEquals(Outcome.HEAL, outcome);
    }

    @Test
    void boundaryConsumesTheCycleEvenWhenGatesFail() {
        Ct8cRegenDriver driver = new Ct8cRegenDriver(() -> 0.0);
        // Full health across a whole window: the boundary resolves to NONE...
        for (int tick = 1; tick <= 40; tick++) {
            assertEquals(Outcome.NONE, driver.tick(20, 20.0, 20.0, true), "full-health tick " + tick);
        }
        // ...and the counter reset, so a now-hurt player heals one window later.
        for (int tick = 1; tick <= 39; tick++) {
            assertEquals(Outcome.NONE, driver.tick(10, 5.0, 20.0, true));
        }
        assertEquals(Outcome.HEAL_AND_DRAIN, driver.tick(10, 5.0, 20.0, true));
    }
}
