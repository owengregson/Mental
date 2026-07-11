package me.vexmc.mental.v5.feature.feedback;

import java.util.Locale;

/**
 * A dust-particle color as three 0–255 RGB channels, parsed from a six-digit
 * {@code RRGGBB} hex string (a leading {@code #} is tolerated). Pure — no
 * PacketEvents, no server — so the death-effects color contract is trivially
 * unit-testable: {@code of("ffaa00")} is {@code (255, 170, 0)}.
 *
 * <p>The runtime feeds these channels straight into PacketEvents'
 * {@code ParticleDustData(scale, red, green, blue)} int constructor, where the
 * wire round-trips each channel through {@code n/255f} — so {@code 0xAA} rides
 * as {@code 170/255 ≈ 0.667}. Keeping the parse here (ints) and the wire mapping
 * in {@link DeathEffectsListener} separates the two concerns cleanly.</p>
 */
record DustColor(int red, int green, int blue) {

    /** Parses a {@code RRGGBB} (or {@code #RRGGBB}) hex string; assumes {@link #isValid}. */
    static DustColor of(String hex) {
        String normalized = strip(hex);
        int rgb = Integer.parseInt(normalized, 16);
        return new DustColor((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    /** Whether {@code hex} is a well-formed six-digit RGB hex (a {@code #} prefix is allowed). */
    static boolean isValid(String hex) {
        if (hex == null) {
            return false;
        }
        String normalized = strip(hex);
        if (normalized.length() != 6) {
            return false;
        }
        for (int i = 0; i < 6; i++) {
            if (Character.digit(normalized.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static String strip(String hex) {
        String trimmed = hex.trim().toLowerCase(Locale.ROOT);
        if (trimmed.startsWith("#")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed;
    }
}
