package me.vexmc.mental.v5.feature.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins the {@code damage-indicators} text contract: final damage rendered in
 * HEARTS (2 damage = 1 heart) at one decimal with the trailing {@code .0}
 * stripped, and {@code {HEALTH}} substituted into the raw ampersand template
 * BEFORE any legacy-code deserialization — the substitution is pure string
 * work, so a template's colour codes ride through untouched.
 */
class IndicatorTextTest {

    @Test
    void heartsFormatOneDecimalStripsTrailingZero() {
        assertEquals("3", IndicatorText.hearts(6.0));
        assertEquals("2.5", IndicatorText.hearts(5.0));
        assertEquals("0.5", IndicatorText.hearts(1.0));
        assertEquals("1.3", IndicatorText.hearts(2.6)); // rounds to one decimal
    }

    @Test
    void templateSubstitutesBeforeLegacyCodes() {
        assertEquals("&f-2.5 &c❤&r", IndicatorText.render("&f-{HEALTH} &c❤&r", 5.0));
    }
}
