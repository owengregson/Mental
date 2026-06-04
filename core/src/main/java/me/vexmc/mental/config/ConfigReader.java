package me.vexmc.mental.config;

import java.util.Locale;
import java.util.Optional;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Validated primitive reads; every out-of-range value warns once and falls back. */
record ConfigReader(@Nullable ConfigurationSection section, @NotNull String prefix, @NotNull ConfigIssues issues) {

    boolean flag(@NotNull String key, boolean fallback) {
        if (section == null || !section.isSet(key)) {
            return fallback;
        }
        if (!section.isBoolean(key)) {
            issues.warn(path(key), "expected true/false, found '" + section.get(key) + "'", fallback);
            return fallback;
        }
        return section.getBoolean(key);
    }

    int intAtLeast(@NotNull String key, int fallback, int minimum) {
        if (section == null || !section.isSet(key)) {
            return fallback;
        }
        if (!section.isInt(key)) {
            issues.warn(path(key), "expected a whole number, found '" + section.get(key) + "'", fallback);
            return fallback;
        }
        int value = section.getInt(key);
        if (value < minimum) {
            issues.warn(path(key), "must be at least " + minimum + ", found " + value, fallback);
            return fallback;
        }
        return value;
    }

    long ticksAtLeast(@NotNull String key, long fallback, long minimum) {
        if (section == null || !section.isSet(key)) {
            return fallback;
        }
        long value = section.getLong(key, Long.MIN_VALUE);
        if (value == Long.MIN_VALUE || !(section.isInt(key) || section.isLong(key))) {
            issues.warn(path(key), "expected a tick count, found '" + section.get(key) + "'", fallback);
            return fallback;
        }
        if (value < minimum) {
            issues.warn(path(key), "must be at least " + minimum + " ticks, found " + value, fallback);
            return fallback;
        }
        return value;
    }

    double number(@NotNull String key, double fallback) {
        if (section == null || !section.isSet(key)) {
            return fallback;
        }
        if (!(section.isDouble(key) || section.isInt(key))) {
            issues.warn(path(key), "expected a number, found '" + section.get(key) + "'", fallback);
            return fallback;
        }
        return section.getDouble(key);
    }

    double numberAtLeast(@NotNull String key, double fallback, double minimum) {
        double value = number(key, fallback);
        if (value < minimum) {
            issues.warn(path(key), "must be at least " + minimum + ", found " + value, fallback);
            return fallback;
        }
        return value;
    }

    <E extends Enum<E>> @NotNull E oneOf(@NotNull String key, @NotNull E fallback, @NotNull Class<E> type) {
        if (section == null || !section.isSet(key)) {
            return fallback;
        }
        String raw = String.valueOf(section.getString(key, ""))
                .trim()
                .toUpperCase(Locale.ROOT)
                .replace('-', '_');
        return Optional.of(raw)
                .map(name -> {
                    try {
                        return Enum.valueOf(type, name);
                    } catch (IllegalArgumentException unknown) {
                        return null;
                    }
                })
                .orElseGet(() -> {
                    issues.warn(path(key), "unknown value '" + section.getString(key) + "'", fallback);
                    return fallback;
                });
    }

    @NotNull ConfigReader sub(@NotNull String key) {
        ConfigurationSection child = section == null ? null : section.getConfigurationSection(key);
        return new ConfigReader(child, path(key), issues);
    }

    private @NotNull String path(@NotNull String key) {
        return prefix.isEmpty() ? key : prefix + "." + key;
    }
}
