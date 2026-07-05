package me.vexmc.mental.v5.feature.pots;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The pure economy arithmetic behind pot-fill's partial fill. The Bukkit fill
 * itself is integration-verified live (PotsSuite); this pins the "how many can
 * the player be given" decision exactly.
 */
class PotFillerTest {

    @Test
    void freePotionsFillEveryEmptySlot() {
        assertEquals(36, PotFiller.affordable(36, 0.0, 0.0));
        assertEquals(5, PotFiller.affordable(5, 0.0, 1000.0));
        // A negative cost is treated as free, never a credit.
        assertEquals(3, PotFiller.affordable(3, -2.0, 0.0));
    }

    @Test
    void costCapsTheFillToWholeAffordablePotions() {
        // 10 each, balance 45 → 4 potions (whole units only), fewer than the empties.
        assertEquals(4, PotFiller.affordable(36, 10.0, 45.0));
        // Balance covers more than the empties → capped by the empty count.
        assertEquals(36, PotFiller.affordable(36, 10.0, 10_000.0));
        // Exactly enough for the empties.
        assertEquals(6, PotFiller.affordable(6, 2.5, 15.0));
    }

    @Test
    void cannotAffordEvenOneYieldsZero() {
        assertEquals(0, PotFiller.affordable(36, 10.0, 9.99));
        assertEquals(0, PotFiller.affordable(36, 10.0, 0.0));
    }

    @Test
    void noEmptySlotsIsAlwaysZero() {
        assertEquals(0, PotFiller.affordable(0, 0.0, 1000.0));
        assertEquals(0, PotFiller.affordable(0, 10.0, 1000.0));
    }
}
