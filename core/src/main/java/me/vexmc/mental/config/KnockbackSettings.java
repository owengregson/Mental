package me.vexmc.mental.config;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Profile selection: which {@link KnockbackProfile} applies where. The
 * profiles themselves are parsed from {@code profiles/*.yml}; this section
 * (knockback.yml) picks the default and the per-world overrides. The
 * built-in {@code legacy-1.7} always exists, even with the file deleted, so
 * resolution can never come up empty.
 */
public record KnockbackSettings(
        boolean enabled,
        @NotNull String defaultProfile,
        @NotNull Map<String, String> perWorld,
        @NotNull Map<String, KnockbackProfile> profiles) {

    static final KnockbackSettings DEFAULTS = new KnockbackSettings(
            true,
            KnockbackProfile.LEGACY_17.name(),
            Map.of(),
            Map.of(KnockbackProfile.LEGACY_17.name(), KnockbackProfile.LEGACY_17));

    /** The default profile — the one applied where no override says otherwise. */
    public @NotNull KnockbackProfile profile() {
        KnockbackProfile selected = profiles.get(defaultProfile);
        return selected != null ? selected : KnockbackProfile.LEGACY_17;
    }

    public @Nullable KnockbackProfile byName(@NotNull String name) {
        return profiles.get(name);
    }

    static @NotNull KnockbackSettings parse(
            boolean enabled,
            @NotNull ConfigReader reader,
            @NotNull Map<String, ConfigurationSection> profileSources,
            @NotNull ConfigIssues issues) {

        Map<String, KnockbackProfile> profiles = new TreeMap<>();
        for (Map.Entry<String, ConfigurationSection> entry : profileSources.entrySet()) {
            String name = entry.getKey().toLowerCase(Locale.ROOT);
            ConfigReader root = new ConfigReader(
                    entry.getValue(), ConfigStore.PROFILES_DIR + "/" + entry.getKey() + ".yml", issues);
            profiles.put(name, KnockbackProfile.parse(
                    name,
                    root.text("display-name", name),
                    root.text("description", ""),
                    root.sub("knockback")));
        }
        // The built-in default survives a deleted or unparseable preset file.
        profiles.putIfAbsent(KnockbackProfile.LEGACY_17.name(), KnockbackProfile.LEGACY_17);

        String selected = reader.text("profile", KnockbackProfile.LEGACY_17.name())
                .toLowerCase(Locale.ROOT);
        if (!profiles.containsKey(selected)) {
            issues.warn(reader.prefix() + ".profile",
                    "unknown profile '" + selected + "' (available: " + profiles.keySet() + ")",
                    KnockbackProfile.LEGACY_17.name());
            selected = KnockbackProfile.LEGACY_17.name();
        }

        Map<String, String> perWorld = new LinkedHashMap<>();
        ConfigurationSection worlds = reader.section() == null
                ? null
                : reader.section().getConfigurationSection("per-world");
        if (worlds != null) {
            for (String world : worlds.getKeys(false)) {
                String profileName = String.valueOf(worlds.getString(world, ""))
                        .trim().toLowerCase(Locale.ROOT);
                if (profiles.containsKey(profileName)) {
                    perWorld.put(world, profileName);
                } else {
                    issues.warn(reader.prefix() + ".per-world." + world,
                            "unknown profile '" + profileName + "'", "entry dropped");
                }
            }
        }

        return new KnockbackSettings(
                enabled, selected, Map.copyOf(perWorld), Map.copyOf(profiles));
    }
}
