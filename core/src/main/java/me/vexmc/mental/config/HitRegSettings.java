package me.vexmc.mental.config;

import org.jetbrains.annotations.NotNull;

public record HitRegSettings(
        boolean enabled,
        int maxCps,
        boolean fastPath,
        boolean preSendFeedback,
        long feedbackMinIntervalMillis,
        boolean simulateCrits,
        boolean legacyToolDamage) {

    /** {@code feedback-min-interval-ms: auto} — derive from the victim's live hurt window. */
    public static final long FEEDBACK_INTERVAL_AUTO = -1L;

    static final HitRegSettings DEFAULTS =
            new HitRegSettings(true, 20, true, true, FEEDBACK_INTERVAL_AUTO, true, true);

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
                fastPath.flag("simulate-crits", DEFAULTS.simulateCrits),
                fastPath.flag("legacy-tool-damage", DEFAULTS.legacyToolDamage));
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
