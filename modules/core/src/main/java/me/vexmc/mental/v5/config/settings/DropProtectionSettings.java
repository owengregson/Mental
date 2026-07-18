package me.vexmc.mental.v5.config.settings;

/**
 * The {@code drop-protection} feature's tunables. When enabled, a slain
 * player's dropped items are pickup-locked to the KILLER for {@code seconds}
 * seconds and glow the chosen colour to the killer alone; everyone else sees a
 * plain drop they cannot pick up until the window elapses, after which the
 * items are free-for-all. Default-OFF, so {@code DEFAULTS} is inert until the
 * module is turned on — the shipped feel is a 15-second window with a GOLD glow.
 */
public record DropProtectionSettings(int seconds, GlowColor glowColor) {

    /** The shipped tune: a 15-second killer-only window with a gold glow. */
    public static final DropProtectionSettings DEFAULTS = new DropProtectionSettings(15, GlowColor.GOLD);

    /**
     * The per-player glow-outline colour. The client draws the glow in the
     * entity's scoreboard-team colour, which is one of the 16 named chat colours
     * — so an arbitrary "warm gold" RGB is not reachable client-side. GOLD
     * ({@code &6}) is the closest to the requested tint; YELLOW ({@code &e}) is
     * offered as the lighter option.
     */
    public enum GlowColor {
        GOLD,
        YELLOW
    }
}
