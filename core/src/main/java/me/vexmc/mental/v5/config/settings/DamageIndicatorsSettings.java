package me.vexmc.mental.v5.config.settings;

/**
 * The {@code damage-indicators} module's tunables. The indicator is an
 * attacker-client-only packet armor stand that pops off the victim's chest on
 * a front-half ring and falls under {@code IndicatorBallistics}; it despawns
 * the instant it reaches the spawn-time ground plane or at
 * {@code lifetimeTicks}. Text templates carry the {@code {HEALTH}} placeholder
 * (final damage in HEARTS, one decimal, trailing .0 stripped); the crit
 * variant fires on an era-crit posture OR damage at/above
 * {@code critThresholdHearts} (HEARTS — 2 damage = 1 heart).
 *
 * @param healText the HEALING-indicator template shown to the last player who
 *        hit the healed player (any heal source), {@code {HEALTH}} = the healed
 *        amount in HEARTS. EMPTY (the DEFAULTS) disables healing indicators
 *        entirely — the era-exact no-op: with no template, no heal sampler is
 *        installed and nothing observes or renders heals.
 */
public record DamageIndicatorsSettings(
        int lifetimeTicks,
        double ringRadius,
        double heightJitter,
        double launchVertical,
        double launchOutward,
        double gravity,
        double drag,
        String text,
        String critText,
        double critThresholdHearts,
        String healText) {

    public static final int MIN_LIFETIME = 1;
    public static final int MAX_LIFETIME = 200;
    public static final double MAX_RADIUS = 4.0;
    public static final double MAX_JITTER = 2.0;
    public static final double MAX_LAUNCH = 2.0;
    public static final double MAX_GRAVITY = 0.5;
    public static final double MIN_DRAG = 0.5;
    public static final double MAX_DRAG = 1.0;

    public static final DamageIndicatorsSettings DEFAULTS = new DamageIndicatorsSettings(
            40, 0.6, 0.3, 0.25, 0.06, 0.05, 0.98,
            "&f-{HEALTH} &c❤&r",
            "&c&l** -{HEALTH} ❤ **",
            5.0,
            "");
}
