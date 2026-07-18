package me.vexmc.mental.v5.config.settings;

/**
 * The hit-registration feature's tunables, ported field-for-field (minus the
 * module toggle, which is {@code Snapshot.enabled(Feature.HIT_REGISTRATION)})
 * from the retired {@code config.HitRegSettings}. The YAML surface is frozen;
 * defaults are byte-identical.
 */
public record HitRegSettings(
        int maxCps,
        boolean fastPath,
        boolean preSendFeedback,
        long feedbackMinIntervalMillis,
        boolean bundleFeedback,
        boolean simulateCrits,
        boolean legacyToolDamage,
        ReachValidation reachValidation) {

    /** {@code feedback-min-interval-ms: auto} — derive from the victim's live hurt window. */
    public static final long FEEDBACK_INTERVAL_AUTO = -1L;

    /**
     * Rewound-reach validation for player-vs-player attack packets. Off by
     * default and deferred entirely to a detected anticheat.
     */
    public record ReachValidation(
            boolean enabled,
            double maxReach,
            double leniency,
            int interpolationOffsetMillis,
            int rewindCapMillis) {

        public static final ReachValidation DEFAULTS = new ReachValidation(false, 3.0, 0.4, 100, 500);
    }

    public static final HitRegSettings DEFAULTS = new HitRegSettings(
            20, true, true, FEEDBACK_INTERVAL_AUTO, true, true, true, ReachValidation.DEFAULTS);
}
