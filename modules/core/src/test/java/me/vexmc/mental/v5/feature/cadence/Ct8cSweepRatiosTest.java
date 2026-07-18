package me.vexmc.mental.v5.feature.cadence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Pins the CT8c Sweeping-Edge ratios and secondary-damage formula (spec §2.3). */
class Ct8cSweepRatiosTest {

    @Test
    void ratiosMatchTheSpec() {
        assertEquals(0.0, Ct8cSweepRatios.ratio(0), 1e-9); // no Sweeping Edge ⇒ no sweep
        assertEquals(0.25, Ct8cSweepRatios.ratio(1), 1e-9);
        assertEquals(1.0 / 3.0, Ct8cSweepRatios.ratio(2), 1e-9);
        assertEquals(0.375, Ct8cSweepRatios.ratio(3), 1e-9);
    }

    @Test
    void negativeLevelsAreNoSweep() {
        assertEquals(0.0, Ct8cSweepRatios.ratio(-1), 1e-9);
    }

    @Test
    void secondaryDamageIsOnePlusRatioTimesMain() {
        // level 2 (ratio 1/3) against a 7-damage main hit → 1 + 7/3.
        assertEquals(1.0 + 7.0 / 3.0, Ct8cSweepRatios.secondaryDamage(2, 7.0), 1e-9);
        // No Sweeping Edge → the flat 1.0 base only (but the gate cancels it anyway).
        assertEquals(1.0, Ct8cSweepRatios.secondaryDamage(0, 7.0), 1e-9);
    }
}
