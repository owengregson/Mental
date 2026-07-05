package me.vexmc.mental.v5.config;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Validated primitive reads; every out-of-range value warns once and falls
 * back. Ported verbatim from the retired {@code config.ConfigReader} — the
 * warn-and-fallback contract every parser depends on is unchanged (only the
 * package moved).
 */
public record ConfigReader(ConfigurationSection section, String prefix, ConfigIssues issues) {

    public boolean flag(String key, boolean fallback) {
        if (section == null || !section.isSet(key)) {
            return fallback;
        }
        if (!section.isBoolean(key)) {
            issues.warn(path(key), "expected true/false, found '" + section.get(key) + "'", fallback);
            return fallback;
        }
        return section.getBoolean(key);
    }

    public int intAtLeast(String key, int fallback, int minimum) {
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

    public long ticksAtLeast(String key, long fallback, long minimum) {
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

    public double number(String key, double fallback) {
        if (section == null || !section.isSet(key)) {
            return fallback;
        }
        if (!(section.isDouble(key) || section.isInt(key))) {
            issues.warn(path(key), "expected a number, found '" + section.get(key) + "'", fallback);
            return fallback;
        }
        return section.getDouble(key);
    }

    public double numberAtLeast(String key, double fallback, double minimum) {
        double value = number(key, fallback);
        if (value < minimum) {
            issues.warn(path(key), "must be at least " + minimum + ", found " + value, fallback);
            return fallback;
        }
        return value;
    }

    /**
     * A number CLAMPED to {@code [minimum, maximum]}. Unlike {@link #numberAtLeast}
     * an out-of-range value is not dropped to the fallback but pulled to the nearest
     * bound (the caller asked for a clamp, so the nearest legal value is the honest
     * result) with one warn. A wrong-typed value still falls back through
     * {@link #number}, and an absent key returns the (in-range) fallback silently.
     */
    public double numberInRange(String key, double fallback, double minimum, double maximum) {
        double value = number(key, fallback);
        if (value < minimum) {
            issues.warn(path(key), "must be at least " + minimum + " — clamped from " + value, minimum);
            return minimum;
        }
        if (value > maximum) {
            issues.warn(path(key), "must be at most " + maximum + " — clamped from " + value, maximum);
            return maximum;
        }
        return value;
    }

    public String text(String key, String fallback) {
        if (section == null || !section.isSet(key)) {
            return fallback;
        }
        Object raw = section.get(key);
        if (!(raw instanceof String value) || value.isBlank()) {
            issues.warn(path(key), "expected text, found '" + raw + "'", fallback);
            return fallback;
        }
        return value.trim();
    }

    public <E extends Enum<E>> E oneOf(String key, E fallback, Class<E> type) {
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

    public List<String> stringList(String key, List<String> fallback) {
        if (section == null || !section.isSet(key)) {
            return fallback;
        }
        if (!section.isList(key)) {
            issues.warn(path(key), "expected a list, found '" + section.get(key) + "'", fallback);
            return fallback;
        }
        return section.getStringList(key);
    }

    public ConfigReader sub(String key) {
        ConfigurationSection child = section == null ? null : section.getConfigurationSection(key);
        return new ConfigReader(child, path(key), issues);
    }

    private String path(String key) {
        return prefix.isEmpty() ? key : prefix + "." + key;
    }
}
