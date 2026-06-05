package me.vexmc.mental.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * File layout, default extraction, and the v1 → v2 migration.
 *
 * <p>Mental's configuration is split by concern: {@code config.yml} is the
 * control panel (module switches, plugin-wide policy), each mechanic family
 * has its own file, and every knockback profile is one file under
 * {@code profiles/}. Bundled files are extracted only when missing — preset
 * edits belong to the server owner and survive every restart; deleting a
 * preset regenerates the original.</p>
 *
 * <p>The v1 layout (everything under {@code modules.*} in one config.yml) is
 * migrated in place: the old file is backed up, the new files are written
 * with the old values carried over, and any tuned v1 knockback block becomes
 * {@code profiles/custom.yml} with {@code profile: custom} selected — a
 * migrated server keeps its exact feel without touching anything.</p>
 */
public final class ConfigStore {

    public static final String MAIN_FILE = "config.yml";
    public static final String KNOCKBACK_FILE = "knockback.yml";
    public static final String HIT_REG_FILE = "hit-registration.yml";
    public static final String LATENCY_FILE = "latency-compensation.yml";
    public static final String PROFILES_DIR = "profiles";
    public static final String V1_BACKUP_FILE = "config-v1-backup.yml";

    /** Presets shipped in the jar; regenerated individually when missing. */
    public static final List<String> BUNDLED_PROFILES =
            List.of("legacy-1.7", "legacy-1.8", "kohi", "mmc", "lunar", "custom");

    private final File dataFolder;
    private final Function<String, InputStream> resources;
    private final Consumer<String> log;

    public ConfigStore(
            @NotNull File dataFolder,
            @NotNull Function<String, InputStream> resources,
            @NotNull Consumer<String> log) {
        this.dataFolder = dataFolder;
        this.resources = resources;
        this.log = log;
    }

    /** Extracts every missing bundled file; never touches an existing one. */
    public void ensureDefaultFiles() {
        extractIfMissing(MAIN_FILE, new File(dataFolder, MAIN_FILE));
        extractIfMissing(KNOCKBACK_FILE, new File(dataFolder, KNOCKBACK_FILE));
        extractIfMissing(HIT_REG_FILE, new File(dataFolder, HIT_REG_FILE));
        extractIfMissing(LATENCY_FILE, new File(dataFolder, LATENCY_FILE));
        File profilesDir = new File(dataFolder, PROFILES_DIR);
        if (!profilesDir.isDirectory() && !profilesDir.mkdirs()) {
            log.accept("Could not create " + profilesDir + " — profiles unavailable");
            return;
        }
        for (String preset : BUNDLED_PROFILES) {
            File file = new File(profilesDir, preset + ".yml");
            extractIfMissing(PROFILES_DIR + "/" + preset + ".yml", file);
            ensureDeliverySection(preset, file);
        }
    }

    /**
     * 1.4.0 introduced the per-profile {@code delivery} block; a preset file
     * from an earlier install lacks it and would parse to the legacy-1.7
     * defaults — wrong for legacy-1.8 and mmc. Bundled presets that predate
     * the knob get exactly the missing section inserted; every user-edited
     * value in the file stays untouched (custom.yml is never modified).
     */
    private void ensureDeliverySection(@NotNull String preset, @NotNull File file) {
        String melee = switch (preset) {
            case "legacy-1.8" -> "immediate";
            case "mmc" -> "immediate";
            default -> "tracker";
        };
        String projectile = "mmc".equals(preset) ? "immediate" : "tracker";
        if ("custom".equals(preset) || !file.exists()) {
            return;
        }
        try {
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            if (content.contains("delivery:")) {
                return;
            }
            String block = "  delivery:\n    melee: " + melee
                    + "\n    projectile: " + projectile + "\n";
            int anchor = content.indexOf("  modifiers:");
            String updated = anchor >= 0
                    ? content.substring(0, anchor) + block + content.substring(anchor)
                    : content + "\n" + block;
            Files.writeString(file.toPath(), updated, StandardCharsets.UTF_8);
            log.accept("Added the 1.4.0 delivery section to profiles/" + preset + ".yml");
        } catch (IOException failure) {
            log.accept("Could not update profiles/" + preset + ".yml: " + failure);
        }
    }

    /**
     * Loads every file into one {@link ConfigSources}. {@code main} is the
     * caller's live {@code plugin.getConfig()} so command writes keep
     * working; satellites and profiles are parsed here, and a file that
     * fails to parse is reported and treated as empty (defaults).
     */
    public @NotNull ConfigSources loadSources(@NotNull FileConfiguration main) {
        Map<String, ConfigurationSection> profiles = new TreeMap<>();
        File profilesDir = new File(dataFolder, PROFILES_DIR);
        File[] profileFiles = profilesDir.listFiles(
                (dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (profileFiles != null) {
            for (File file : profileFiles) {
                String stem = file.getName().substring(0, file.getName().length() - 4);
                profiles.put(stem, loadYaml(file, PROFILES_DIR + "/" + file.getName()));
            }
        }
        return new ConfigSources(
                main,
                loadYaml(new File(dataFolder, KNOCKBACK_FILE), KNOCKBACK_FILE),
                loadYaml(new File(dataFolder, HIT_REG_FILE), HIT_REG_FILE),
                loadYaml(new File(dataFolder, LATENCY_FILE), LATENCY_FILE),
                profiles);
    }

    /* ------------------------------------------------------------------ */
    /*  v1 migration                                                       */
    /* ------------------------------------------------------------------ */

    /** A v1 config.yml keeps whole module sections under {@code modules.*}. */
    public static boolean isLegacyLayout(@NotNull ConfigurationSection main) {
        ConfigurationSection modules = main.getConfigurationSection("modules");
        if (modules == null) {
            return false;
        }
        for (String key : modules.getKeys(false)) {
            if (modules.isConfigurationSection(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rewrites a v1 layout into the split files, carrying every value over.
     * Returns true when a migration ran (the caller must reload its bound
     * config.yml afterwards).
     */
    public boolean migrateLegacyLayout(@NotNull FileConfiguration oldMain) {
        if (!isLegacyLayout(oldMain)) {
            return false;
        }
        File mainFile = new File(dataFolder, MAIN_FILE);
        try {
            Files.copy(mainFile.toPath(), new File(dataFolder, V1_BACKUP_FILE).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            log.accept("Could not back up the v1 config.yml — migration aborted: " + failure);
            return false;
        }

        // The old knockback block IS a profile body (minus 'enabled'). When
        // it was tuned away from the legacy defaults it becomes the custom
        // profile and gets selected; an untouched block keeps legacy-1.7.
        ConfigIssues scratch = new ConfigIssues();
        ConfigurationSection oldKnockback = oldMain.getConfigurationSection("modules.knockback");
        KnockbackProfile oldProfile = KnockbackProfile.parse(
                "custom", "Custom", "",
                new ConfigReader(oldKnockback, "", scratch));
        boolean tuned = !oldProfile.sameValues(KnockbackProfile.LEGACY_17);

        Map<String, Object> mainValues = new LinkedHashMap<>();
        for (String module : List.of("hit-registration", "knockback", "latency-compensation",
                "fishing-knockback", "rod-velocity", "projectile-knockback")) {
            boolean enabled = oldMain.getBoolean("modules." + module + ".enabled", true);
            if (!enabled) {
                mainValues.put("modules." + module, false);
            }
        }
        copyLeaves(oldMain.getConfigurationSection("anticheat"), "anticheat", mainValues);
        copyLeaves(oldMain.getConfigurationSection("compatibility"), "compatibility", mainValues);
        copyLeaves(oldMain.getConfigurationSection("debug"), "debug", mainValues);

        Map<String, Object> knockbackValues = new LinkedHashMap<>();
        if (tuned) {
            knockbackValues.put("knockback.profile", "custom");
        }
        copyLeaves(oldMain.getConfigurationSection("modules.fishing-knockback"),
                "fishing-knockback", knockbackValues, "enabled");
        copyLeaves(oldMain.getConfigurationSection("modules.projectile-knockback"),
                "projectile-knockback", knockbackValues, "enabled");

        Map<String, Object> hitRegValues = new LinkedHashMap<>();
        copyLeaves(oldMain.getConfigurationSection("modules.hit-registration"),
                "hit-registration", hitRegValues, "enabled");

        Map<String, Object> latencyValues = new LinkedHashMap<>();
        copyLeaves(oldMain.getConfigurationSection("modules.latency-compensation"),
                "latency-compensation", latencyValues, "enabled");

        writeFromTemplate(MAIN_FILE, mainFile, mainValues);
        writeFromTemplate(KNOCKBACK_FILE, new File(dataFolder, KNOCKBACK_FILE), knockbackValues);
        writeFromTemplate(HIT_REG_FILE, new File(dataFolder, HIT_REG_FILE), hitRegValues);
        writeFromTemplate(LATENCY_FILE, new File(dataFolder, LATENCY_FILE), latencyValues);
        if (tuned) {
            writeMigratedCustomProfile(oldKnockback);
        }

        log.accept("Migrated the v1 config.yml into the split layout (backup: "
                + V1_BACKUP_FILE + ")"
                + (tuned ? "; your tuned knockback values now live in profiles/custom.yml"
                        + " and 'custom' is the selected profile." : "."));
        return true;
    }

    @SuppressWarnings("deprecation") // options().header(String): the only header API on the 1.17 floor
    private void writeMigratedCustomProfile(@Nullable ConfigurationSection oldKnockback) {
        YamlConfiguration profile = new YamlConfiguration();
        profile.options().header(
                "custom — migrated from your v1 config.yml knockback block.\n"
                        + "The fully commented schema for every knob ships in the other\n"
                        + "profiles/*.yml presets (delete one to regenerate it).");
        profile.set("display-name", "Custom");
        profile.set("description", "Migrated from the v1 config.yml.");
        Map<String, Object> values = new LinkedHashMap<>();
        copyLeaves(oldKnockback, "knockback", values, "enabled");
        values.forEach(profile::set);
        File profilesDir = new File(dataFolder, PROFILES_DIR);
        if (!profilesDir.isDirectory() && !profilesDir.mkdirs()) {
            log.accept("Could not create " + profilesDir + " for the migrated custom profile");
            return;
        }
        try {
            profile.save(new File(profilesDir, "custom.yml"));
        } catch (IOException failure) {
            log.accept("Could not write the migrated profiles/custom.yml: " + failure);
        }
    }

    /** Copies every leaf of {@code section} into {@code values} under {@code prefix}. */
    private static void copyLeaves(
            @Nullable ConfigurationSection section,
            @NotNull String prefix,
            @NotNull Map<String, Object> values,
            @NotNull String... skipKeys) {
        if (section == null) {
            return;
        }
        List<String> skip = List.of(skipKeys);
        for (Map.Entry<String, Object> entry : section.getValues(true).entrySet()) {
            if (entry.getValue() instanceof ConfigurationSection || skip.contains(entry.getKey())) {
                continue;
            }
            values.put(prefix + "." + entry.getKey(), entry.getValue());
        }
    }

    /**
     * Writes {@code target} from the bundled template, overlaying carried
     * values. An empty overlay copies the template verbatim (comments
     * intact); a non-empty overlay re-serializes, which preserves comments
     * on 1.18.1+ runtimes and drops them on 1.17.x — values always win.
     */
    private void writeFromTemplate(
            @NotNull String resource, @NotNull File target, @NotNull Map<String, Object> values) {
        String template = readResource(resource);
        if (template == null) {
            log.accept("Bundled template " + resource + " is missing from the jar");
            return;
        }
        try {
            if (values.isEmpty()) {
                Files.writeString(target.toPath(), template, StandardCharsets.UTF_8);
                return;
            }
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new StringReader(template));
            values.forEach(yaml::set);
            yaml.save(target);
        } catch (IOException | InvalidConfigurationException failure) {
            log.accept("Could not write " + target.getName() + " during migration: " + failure);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  IO helpers                                                         */
    /* ------------------------------------------------------------------ */

    private void extractIfMissing(@NotNull String resource, @NotNull File target) {
        if (target.exists()) {
            return;
        }
        String content = readResource(resource);
        if (content == null) {
            log.accept("Bundled resource " + resource + " is missing from the jar");
            return;
        }
        try {
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                log.accept("Could not create " + parent + " for " + resource);
                return;
            }
            Files.writeString(target.toPath(), content, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            log.accept("Could not extract " + resource + ": " + failure);
        }
    }

    private @Nullable String readResource(@NotNull String name) {
        try (InputStream stream = resources.apply(name)) {
            if (stream == null) {
                return null;
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            log.accept("Could not read bundled resource " + name + ": " + failure);
            return null;
        }
    }

    private @NotNull ConfigurationSection loadYaml(@NotNull File file, @NotNull String label) {
        YamlConfiguration yaml = new YamlConfiguration();
        if (!file.isFile()) {
            return yaml;
        }
        try (InputStreamReader reader = new InputStreamReader(
                Files.newInputStream(file.toPath()), StandardCharsets.UTF_8)) {
            yaml.load(reader);
        } catch (IOException | InvalidConfigurationException failure) {
            log.accept(label + " could not be parsed — using defaults for it: "
                    + failure.getMessage());
        }
        return yaml;
    }
}
