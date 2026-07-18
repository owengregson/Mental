package me.vexmc.mental.v5.feature.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins the {@code damage-indicators} text contract: final damage rendered in
 * DAMAGE POINTS (HP — the raw amount the health bar loses, NOT halved into
 * hearts) at one decimal with the trailing {@code .0} stripped, and
 * {@code {HEALTH}} substituted into the raw ampersand template BEFORE any
 * legacy-code deserialization — the substitution is pure string work, so a
 * template's colour codes ride through untouched. A bare-fist hit reads
 * {@code 1}, not {@code 0.5} (the 2026-07-12 owner directive: show the number
 * the player actually loses).
 */
class IndicatorTextTest {

    @Test
    void pointsFormatOneDecimalStripsTrailingZero() {
        assertEquals("6", IndicatorText.points(6.0));
        assertEquals("5", IndicatorText.points(5.0));
        assertEquals("1", IndicatorText.points(1.0)); // a bare-fist hit, in HP
        assertEquals("1.5", IndicatorText.points(1.5)); // a crit fist
        assertEquals("0.5", IndicatorText.points(0.5)); // a mid-window delta, honestly small
        assertEquals("2.6", IndicatorText.points(2.6)); // rounds to one decimal
    }

    @Test
    void templateSubstitutesBeforeLegacyCodes() {
        assertEquals("&f-5 &c❤&r", IndicatorText.render("&f-{HEALTH} &c❤&r", 5.0));
    }
}
