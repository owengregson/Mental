package me.vexmc.mental.v5.config.settings;

/**
 * The {@code fast-pots} feature's tunables (the POTS family, owner directive
 * 2026-07-04; speed-band + lead round 2026-07-07). When a player throws a splash
 * potion steeply downward, the feature re-aims it at the predicted position of the
 * thrower's own feet within a bounded speed band — the classic "fast pot"
 * self-heal, made reliable.
 *
 * <ul>
 *   <li>{@code angleDegrees} — how far below horizontal the throw must aim before
 *       the redirect kicks in, as a Bukkit pitch threshold: the redirect fires
 *       when {@code location.getPitch() > angleDegrees}. Parse-clamped to
 *       {@code [0, 90]} (0 = redirect any downward-or-level throw, 90 = only a
 *       perfectly vertical throw). Default {@code 35.0}.</li>
 *   <li>{@code minSpeedMultiplier} — the FLOOR on the redirected launch speed, as a
 *       factor of the vanilla launch speed. The solver never ships slower than this
 *       (a fast pot always has punch). Parse-clamped to {@code [0.05, 1.0]}
 *       (positive, never above vanilla). Default {@code 0.5}.</li>
 *   <li>{@code maxSpeedMultiplier} — the CEILING on the redirected launch speed, as
 *       a factor of the vanilla launch speed. The solver never ships faster than
 *       this (capped so the potion cannot outrun its own splash resolution).
 *       Parse-clamped to {@code [1.0, 5.0]}. Default {@code 1.5}.</li>
 *   <li>{@code leadTicks} — a forward LEAD, in ticks of the thrower's own motion,
 *       applied to the predicted feet: the burst is aimed at
 *       {@code feet + throwerVel·(N + leadTicks)} rather than at the bare arrival
 *       tick {@code N}. This lands the cloud slightly <em>in front of</em> where the
 *       feet will be at impact, so a running player moves INTO it (and it absorbs
 *       the sub-tick ground-crossing lag that otherwise plants the burst behind the
 *       feet). Parse-clamped to {@code [0, 5]}; {@code 0} reproduces the un-led feet
 *       (byte-identical to the pre-lead intent). Default {@code 1.0}.</li>
 * </ul>
 *
 * <p>Because {@code minSpeedMultiplier} is clamped to {@code ≤ 1.0} and
 * {@code maxSpeedMultiplier} to {@code ≥ 1.0}, the band is always well-ordered
 * ({@code min ≤ 1 ≤ max}) — no explicit {@code min ≤ max} reconciliation is needed.
 * The exact-ballistic aim ({@link me.vexmc.mental.v5.feature.pots.PotsAim}) spends
 * the least speed that lands the burst exactly on the led feet <em>within</em>
 * {@code [min, max] × vanilla}; only the reachable impact-tick {@code N} is
 * restricted — every velocity component stays free and the spawn is never moved.</p>
 *
 * <p>Shallower throws (pitch at or below the threshold) are left byte-for-byte
 * untouched — zero-touch outside the angle band.</p>
 */
public record FastPotsSettings(
        double angleDegrees,
        double minSpeedMultiplier,
        double maxSpeedMultiplier,
        double leadTicks) {

    /** Pitch-threshold clamp bounds — a Bukkit pitch is always within [-90, 90]. */
    public static final double MIN_ANGLE = 0.0;
    public static final double MAX_ANGLE = 90.0;

    /** Floor-multiplier clamp bounds — positive, never above vanilla (so min ≤ 1 ≤ max). */
    public static final double MIN_MIN_MULTIPLIER = 0.05;
    public static final double MAX_MIN_MULTIPLIER = 1.0;

    /** Ceiling-multiplier clamp bounds — never below vanilla, capped for splash sanity. */
    public static final double MIN_MAX_MULTIPLIER = 1.0;
    public static final double MAX_MAX_MULTIPLIER = 5.0;

    /** Forward-lead clamp bounds, in ticks of thrower motion. */
    public static final double MIN_LEAD = 0.0;
    public static final double MAX_LEAD = 5.0;

    public static final FastPotsSettings DEFAULTS = new FastPotsSettings(35.0, 0.5, 1.5, 1.0);
}
