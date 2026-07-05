package me.vexmc.mental.v5.config.settings;

/**
 * The {@code fast-pots} feature's tunables (the POTS family, owner directive
 * 2026-07-04). When a player throws a splash potion steeply downward, the
 * feature re-aims it at the predicted position of the thrower's own feet at a
 * multiplied launch speed — the classic "fast pot" self-heal, made reliable.
 *
 * <ul>
 *   <li>{@code angleDegrees} — how far below horizontal the throw must aim before
 *       the redirect kicks in, as a Bukkit pitch threshold: the redirect fires
 *       when {@code location.getPitch() > angleDegrees}. Parse-clamped to
 *       {@code [0, 90]} (0 = redirect any downward-or-level throw, 90 = only a
 *       perfectly vertical throw). Default {@code 35.0}.</li>
 *   <li>{@code speedMultiplier} — the factor applied to the vanilla launch speed
 *       for the redirected potion. Parse-clamped to {@code [1.0, 5.0]} (never
 *       slower than vanilla, capped so the potion cannot outrun its own splash
 *       resolution). Default {@code 3.0}.</li>
 * </ul>
 *
 * <p>Shallower throws (pitch at or below the threshold) are left byte-for-byte
 * untouched — zero-touch outside the angle band.</p>
 */
public record FastPotsSettings(
        double angleDegrees,
        double speedMultiplier) {

    /** Pitch-threshold clamp bounds — a Bukkit pitch is always within [-90, 90]. */
    public static final double MIN_ANGLE = 0.0;
    public static final double MAX_ANGLE = 90.0;

    /** Speed-multiplier clamp bounds — never below vanilla, capped for splash sanity. */
    public static final double MIN_MULTIPLIER = 1.0;
    public static final double MAX_MULTIPLIER = 5.0;

    public static final FastPotsSettings DEFAULTS = new FastPotsSettings(35.0, 3.0);
}
