package me.vexmc.mental.v5.feature.feedback;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * {@code {HEALTH}} templating: final damage rendered in DAMAGE POINTS (HP — the
 * raw amount the health bar loses, NOT halved into hearts), one decimal,
 * trailing {@code .0} stripped. A bare-fist hit reads {@code 1}, not
 * {@code 0.5} — the 2026-07-12 owner directive: show the number the player
 * actually loses. Substitution happens on the raw ampersand template BEFORE any
 * legacy-code deserialization — pure string work, so the template's colour
 * codes ride through untouched. The {@code {HEALTH}} placeholder name and the ❤
 * glyphs in the default templates are cosmetic and unchanged; only the number's
 * UNIT moved (hearts → damage points).
 */
final class IndicatorText {

    private IndicatorText() {}

    static String points(double finalDamage) {
        BigDecimal points = BigDecimal.valueOf(finalDamage)
                .setScale(1, RoundingMode.HALF_UP)
                .stripTrailingZeros();
        return points.toPlainString();
    }

    static String render(String template, double finalDamage) {
        return template.replace("{HEALTH}", points(finalDamage));
    }
}
