package me.vexmc.mental.config;

import org.jetbrains.annotations.NotNull;

public record HitRegSettings(
        boolean enabled,
        int maxCps,
        boolean fastPath,
        boolean preSendFeedback,
        long feedbackMinIntervalMillis,
        boolean simulateCrits,
        boolean legacyToolDamage,
        boolean resetAttackCooldown) {

    static final HitRegSettings DEFAULTS =
            new HitRegSettings(true, 20, true, true, 500L, true, true, true);

    public boolean rateLimited() {
        return maxCps > 0;
    }

    static @NotNull HitRegSettings parse(@NotNull ConfigReader reader) {
        ConfigReader fastPath = reader.sub("fast-path");
        return new HitRegSettings(
                reader.flag("enabled", DEFAULTS.enabled),
                reader.intAtLeast("max-cps", DEFAULTS.maxCps, 0),
                fastPath.flag("enabled", DEFAULTS.fastPath),
                fastPath.flag("pre-send-feedback", DEFAULTS.preSendFeedback),
                fastPath.ticksAtLeast("feedback-min-interval-ms", DEFAULTS.feedbackMinIntervalMillis, 0),
                fastPath.flag("simulate-crits", DEFAULTS.simulateCrits),
                fastPath.flag("legacy-tool-damage", DEFAULTS.legacyToolDamage),
                fastPath.flag("reset-attack-cooldown", DEFAULTS.resetAttackCooldown));
    }
}
