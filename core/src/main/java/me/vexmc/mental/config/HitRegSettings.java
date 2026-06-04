package me.vexmc.mental.config;

import org.jetbrains.annotations.NotNull;

public record HitRegSettings(
        boolean enabled,
        int maxCps,
        boolean fastPath,
        boolean preSendFeedback,
        long feedbackMinIntervalMillis,
        boolean bundleFeedback,
        boolean simulateCrits,
        boolean legacyToolDamage,
        @NotNull ReachValidation reachValidation) {

    /** {@code feedback-min-interval-ms: auto} — derive from the victim's live hurt window. */
    public static final long FEEDBACK_INTERVAL_AUTO = -1L;

    /**
     * Rewound-reach validation for player-vs-player attack packets: hits
     * beyond {@code maxReach + leniency} against every candidate instant
     * (history around now − ping − interpolation, plus live) are dropped at
     * the packet layer. Off by default and deferred entirely to a detected
     * anticheat.
     */
    public record ReachValidation(
            boolean enabled,
            double maxReach,
            double leniency,
            int interpolationOffsetMillis,
            int rewindCapMillis) {

        static final ReachValidation DEFAULTS = new ReachValidation(false, 3.0, 0.4, 100, 500);

        static @NotNull ReachValidation parse(@NotNull ConfigReader reader) {
            return new ReachValidation(
                    reader.flag("enabled", DEFAULTS.enabled),
                    reader.numberAtLeast("max-reach", DEFAULTS.maxReach, 0.5),
                    reader.numberAtLeast("leniency", DEFAULTS.leniency, 0),
                    reader.intAtLeast("interpolation-offset-ms", DEFAULTS.interpolationOffsetMillis, 0),
                    reader.intAtLeast("rewind-cap-ms", DEFAULTS.rewindCapMillis, 0));
        }
    }

    static final HitRegSettings DEFAULTS = new HitRegSettings(
            true, 20, true, true, FEEDBACK_INTERVAL_AUTO, true, true, true,
            ReachValidation.DEFAULTS);

    public boolean rateLimited() {
        return maxCps > 0;
    }

    static @NotNull HitRegSettings parse(boolean enabled, @NotNull ConfigReader reader) {
        ConfigReader fastPath = reader.sub("fast-path");
        return new HitRegSettings(
                enabled,
                reader.intAtLeast("max-cps", DEFAULTS.maxCps, 0),
                fastPath.flag("enabled", DEFAULTS.fastPath),
                fastPath.flag("pre-send-feedback", DEFAULTS.preSendFeedback),
                parseFeedbackInterval(fastPath),
                fastPath.flag("bundle-feedback", DEFAULTS.bundleFeedback),
                fastPath.flag("simulate-crits", DEFAULTS.simulateCrits),
                fastPath.flag("legacy-tool-damage", DEFAULTS.legacyToolDamage),
                ReachValidation.parse(reader.sub("reach-validation")));
    }

    /**
     * {@code auto} (the default) tracks the victim's {@code maximumNoDamageTicks}
     * — half the window, vanilla's re-hit boundary — so plugins that retune the
     * hurt window (OldCombatMechanics' attack-frequency) are honored without
     * configuration. A number fixes the interval in milliseconds.
     */
    private static long parseFeedbackInterval(@NotNull ConfigReader fastPath) {
        if (fastPath.section() == null || !fastPath.section().isSet("feedback-min-interval-ms")) {
            return DEFAULTS.feedbackMinIntervalMillis;
        }
        if ("auto".equalsIgnoreCase(String.valueOf(fastPath.section().get("feedback-min-interval-ms")))) {
            return FEEDBACK_INTERVAL_AUTO;
        }
        return fastPath.ticksAtLeast("feedback-min-interval-ms", 500L, 0);
    }
}
