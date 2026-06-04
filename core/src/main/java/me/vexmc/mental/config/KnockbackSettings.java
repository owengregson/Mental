package me.vexmc.mental.config;

import org.jetbrains.annotations.NotNull;

public record KnockbackSettings(
        boolean enabled,
        double baseHorizontal,
        double baseVertical,
        double extraHorizontal,
        double extraVertical,
        double limitVertical,
        double limitHorizontal,
        double frictionX,
        double frictionY,
        double frictionZ,
        double sprintFactor,
        boolean honorArmorResistance,
        boolean shieldBlockingCancels) {

    static final KnockbackSettings DEFAULTS = new KnockbackSettings(
            true, 0.4, 0.4, 0.5, 0.1, 0.4, -1.0, 0.5, 0.5, 0.5, 1.0, false, true);

    public boolean limitsVertical() {
        return limitVertical > 0;
    }

    public boolean limitsHorizontal() {
        return limitHorizontal > 0;
    }

    static @NotNull KnockbackSettings parse(@NotNull ConfigReader reader) {
        ConfigReader base = reader.sub("base");
        ConfigReader extra = reader.sub("extra");
        ConfigReader limits = reader.sub("limits");
        ConfigReader friction = reader.sub("friction");
        ConfigReader modifiers = reader.sub("modifiers");
        return new KnockbackSettings(
                reader.flag("enabled", DEFAULTS.enabled),
                base.numberAtLeast("horizontal", DEFAULTS.baseHorizontal, 0),
                base.numberAtLeast("vertical", DEFAULTS.baseVertical, 0),
                extra.numberAtLeast("horizontal", DEFAULTS.extraHorizontal, 0),
                extra.numberAtLeast("vertical", DEFAULTS.extraVertical, 0),
                limits.number("vertical", DEFAULTS.limitVertical),
                limits.number("horizontal", DEFAULTS.limitHorizontal),
                friction.numberAtLeast("x", DEFAULTS.frictionX, 0),
                friction.numberAtLeast("y", DEFAULTS.frictionY, 0),
                friction.numberAtLeast("z", DEFAULTS.frictionZ, 0),
                modifiers.numberAtLeast("sprint", DEFAULTS.sprintFactor, 0),
                modifiers.flag("armor-resistance", DEFAULTS.honorArmorResistance),
                modifiers.flag("shield-blocking-cancels", DEFAULTS.shieldBlockingCancels));
    }
}
