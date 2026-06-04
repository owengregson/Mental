package me.vexmc.mental.config;

import java.util.EnumSet;
import java.util.Set;
import me.vexmc.mental.common.debug.DebugCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;

public record DebugSettings(boolean enabled, @NotNull Set<DebugCategory> categories) {

    static final DebugSettings DEFAULTS = new DebugSettings(false, Set.of());

    static @NotNull DebugSettings parse(@NotNull ConfigReader reader) {
        boolean enabled = reader.flag("enabled", DEFAULTS.enabled);
        ConfigurationSection categorySection =
                reader.section() == null ? null : reader.section().getConfigurationSection("categories");
        if (categorySection == null) {
            return new DebugSettings(enabled, DEFAULTS.categories);
        }
        EnumSet<DebugCategory> active = EnumSet.noneOf(DebugCategory.class);
        for (String key : categorySection.getKeys(false)) {
            DebugCategory.byKey(key).ifPresentOrElse(
                    category -> {
                        if (categorySection.getBoolean(key)) {
                            active.add(category);
                        }
                    },
                    () -> reader.issues().warn("debug.categories." + key, "unknown debug category", "ignored"));
        }
        return new DebugSettings(enabled, Set.copyOf(active));
    }
}
