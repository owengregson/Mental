package me.vexmc.mental.v5.feature.feedback;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * {@code {HEALTH}} templating: final damage rendered in HEARTS (2 damage =
 * 1 heart), one decimal, trailing {@code .0} stripped. Substitution happens on
 * the raw ampersand template BEFORE any legacy-code deserialization — pure
 * string work, so the template's colour codes ride through untouched.
 */
final class IndicatorText {

    private IndicatorText() {}

    static String hearts(double finalDamage) {
        BigDecimal hearts = BigDecimal.valueOf(finalDamage / 2.0)
                .setScale(1, RoundingMode.HALF_UP)
                .stripTrailingZeros();
        return hearts.toPlainString();
    }

    static String render(String template, double finalDamage) {
        return template.replace("{HEALTH}", hearts(finalDamage));
    }
}
