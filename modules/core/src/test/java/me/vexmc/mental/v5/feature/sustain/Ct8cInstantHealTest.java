package me.vexmc.mental.v5.feature.sustain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins the Instant Health substitution (spec §2.8): a vanilla heal amount
 * ({@code 4·2^amp}) is mapped to the CT8c value ({@code 6·2^amp}) — Instant
 * Health I heals 6, II heals 12, III heals 24.
 */
class Ct8cInstantHealTest {

    private static final double EPSILON = 1.0e-9;

    @Test
    void substitutesVanillaHealToCt8cValue() {
        assertEquals(6.0, Ct8cInstantHeal.substituted(4.0), EPSILON);  // Instant Health I: 4 → 6
        assertEquals(12.0, Ct8cInstantHeal.substituted(8.0), EPSILON); // Instant Health II: 8 → 12
        assertEquals(24.0, Ct8cInstantHeal.substituted(16.0), EPSILON); // Instant Health III: 16 → 24
    }

    @Test
    void recoversAmplifierFromTheVanillaAmount() {
        assertEquals(0, Ct8cInstantHeal.amplifierOf(4.0));
        assertEquals(1, Ct8cInstantHeal.amplifierOf(8.0));
        assertEquals(2, Ct8cInstantHeal.amplifierOf(16.0));
    }

    @Test
    void amplifierFloorsAtZero() {
        // A below-base amount (never a real vanilla heal) resolves to amplifier 0, not negative.
        assertEquals(0, Ct8cInstantHeal.amplifierOf(2.0));
        assertEquals(6.0, Ct8cInstantHeal.substituted(2.0), EPSILON);
    }

    @Test
    void nonPositiveAmountPassesThroughUntouched() {
        // A degenerate event never conjures a phantom heal.
        assertEquals(0.0, Ct8cInstantHeal.substituted(0.0), EPSILON);
        assertEquals(-3.0, Ct8cInstantHeal.substituted(-3.0), EPSILON);
    }
}
