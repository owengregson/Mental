package me.vexmc.mental.platform.debug;

import java.util.Locale;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/** Every verbose-logging channel Mental can emit on. */
public enum DebugCategory {
    HITREG,
    KNOCKBACK,
    JOURNAL,
    COMPENSATION,
    FISHING,
    PROJECTILE,
    PACKETS,
    ANTICHEAT,
    SCHEDULING,
    COMMANDS,
    CONFIG;

    private final String key = name().toLowerCase(Locale.ROOT);

    public @NotNull String key() {
        return key;
    }

    public static @NotNull Optional<DebugCategory> byKey(@NotNull String key) {
        for (DebugCategory category : values()) {
            if (category.key.equalsIgnoreCase(key)) {
                return Optional.of(category);
            }
        }
        return Optional.empty();
    }
}
