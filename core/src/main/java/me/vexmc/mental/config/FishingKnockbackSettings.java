package me.vexmc.mental.config;

import org.jetbrains.annotations.NotNull;

public record FishingKnockbackSettings(
        boolean enabled,
        double damage,
        @NotNull DragInPolicy cancelDraggingIn,
        boolean knockbackNonPlayerEntities) {

    static final FishingKnockbackSettings DEFAULTS =
            new FishingKnockbackSettings(true, 0.0001, DragInPolicy.PLAYERS, false);

    static @NotNull FishingKnockbackSettings parse(@NotNull ConfigReader reader) {
        return new FishingKnockbackSettings(
                reader.flag("enabled", DEFAULTS.enabled),
                reader.numberAtLeast("damage", DEFAULTS.damage, 0),
                reader.oneOf("cancel-dragging-in", DEFAULTS.cancelDraggingIn, DragInPolicy.class),
                reader.flag("knockback-non-player-entities", DEFAULTS.knockbackNonPlayerEntities));
    }
}
