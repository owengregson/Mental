package me.vexmc.mental.v5.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * The explicit {@code config-version} migration chain (spec §10, fixing mandate
 * §2.7 "written but never read"). The version is now READ from config.yml — a
 * present stamp is authoritative; an absent one means version 1 if the file is
 * the v1 single-file shape, else the current version (a fresh or already-split
 * tree needs no v1 split).
 *
 * <ul>
 *   <li>{@code 1 → 2}: the retired {@code ConfigStore.migrateLegacyLayout} —
 *       back up config.yml, split it into the per-concern files, and turn a
 *       tuned v1 knockback block into {@code profiles/custom.yml} with
 *       {@code custom} selected.</li>
 *   <li>{@code 2 → 3}: back up config.yml, create the empty
 *       {@code state/overrides.yml}, and stamp {@code config-version: 3}. No
 *       key renames.</li>
 * </ul>
 *
 * <p>Each step backs up into {@code config-backup-v<N>/} first and is
 * idempotent — running the chain on an already-migrated tree is a no-op.</p>
 */
public final class Migrations {

    public static final int CURRENT_VERSION = 3;

    private final Path dataDir;
    private final Function<String, InputStream> resources;
    private final Consumer<String> log;

    public Migrations(Path dataDir, Function<String, InputStream> resources, Consumer<String> log) {
        this.dataDir = dataDir;
        this.resources = resources;
        this.log = log;
    }

    /** Classpath-backed migrator with a silent log — the production wiring shape. */
    public Migrations(Path dataDir) {
        this(dataDir, name -> Migrations.class.getClassLoader().getResourceAsStream(name), message -> {});
    }

    /** The steps a run applied (the target versions), in order. */
    public record Result(int fromVersion, int toVersion, List<Integer> stepsApplied) {}

    /** Runs every applicable step in order and returns what changed. */
    public Result migrate() {
        int from = detectVersion();
        int version = from;
        List<Integer> steps = new ArrayList<>();
        if (version < 2 && isLegacyLayout(loadMain())) {
            migrateV1toV2();
            steps.add(2);
            version = 2;
        }
        if (version < 3 && Files.isRegularFile(dataDir.resolve(ConfigStore.MAIN_FILE))) {
            migrateV2toV3();
            steps.add(3);
            version = 3;
        }
        return new Result(from, version, steps);
    }

    /** The config-version the tree is at: present stamp, else v1 by shape, else current. */
    public int detectVersion() {
        Path main = dataDir.resolve(ConfigStore.MAIN_FILE);
        if (!Files.isRegularFile(main)) {
            return CURRENT_VERSION; // nothing on disk to migrate
        }
        YamlConfiguration config = loadMain();
        if (config.isSet("config-version")) {
            return config.getInt("config-version");
        }
        return isLegacyLayout(config) ? 1 : CURRENT_VERSION;
    }

    /** A v1 config.yml keeps whole module sections under {@code modules.*}. */
    public static boolean isLegacyLayout(ConfigurationSection main) {
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

    /* ------------------------------------------------------------------ */
    /*  1 -> 2 : split the single-file layout                              */
    /* ------------------------------------------------------------------ */

    private void migrateV1toV2() {
        YamlConfiguration oldMain = loadMain();
        backup(1);

        ConfigurationSection oldKnockback = oldMain.getConfigurationSection("modules.knockback");
        KnockbackProfile oldProfile = ProfileParser.parse(
                "custom", "Custom", "", new ConfigReader(oldKnockback, "", new ConfigIssues()));
        boolean tuned = !oldProfile.sameValues(KnockbackProfile.LEGACY_17);

        Map<String, Object> mainValues = new LinkedHashMap<>();
        for (String module : List.of("hit-registration", "knockback", "latency-compensation",
                "fishing-knockback", "rod-velocity", "projectile-knockback")) {
            if (!oldMain.getBoolean("modules." + module + ".enabled", true)) {
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

        writeFromTemplate(ConfigStore.MAIN_FILE, dataDir.resolve(ConfigStore.MAIN_FILE), mainValues);
        writeFromTemplate(ConfigStore.KNOCKBACK_FILE, dataDir.resolve(ConfigStore.KNOCKBACK_FILE), knockbackValues);
        writeFromTemplate(ConfigStore.HIT_REG_FILE, dataDir.resolve(ConfigStore.HIT_REG_FILE), hitRegValues);
        writeFromTemplate(ConfigStore.LATENCY_FILE, dataDir.resolve(ConfigStore.LATENCY_FILE), latencyValues);
        if (tuned) {
            writeMigratedCustomProfile(oldKnockback);
        }

        log.accept("Migrated the v1 config.yml into the split layout (backup: config-backup-v1/)"
                + (tuned ? "; your tuned knockback values now live in profiles/custom.yml"
                        + " and 'custom' is the selected profile." : "."));
    }

    private void writeMigratedCustomProfile(ConfigurationSection oldKnockback) {
        YamlConfiguration profile = new YamlConfiguration();
        profile.set("display-name", "Custom");
        profile.set("description", "Migrated from the v1 config.yml.");
        Map<String, Object> values = new LinkedHashMap<>();
        copyLeaves(oldKnockback, "knockback", values, "enabled");
        values.forEach(profile::set);
        Path profilesDir = dataDir.resolve(ConfigStore.PROFILES_DIR);
        try {
            Files.createDirectories(profilesDir);
            Files.writeString(profilesDir.resolve("custom.yml"), profile.saveToString(),
                    StandardCharsets.UTF_8);
        } catch (IOException failure) {
            log.accept("Could not write the migrated profiles/custom.yml: " + failure);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  2 -> 3 : create the overlay, stamp the version                     */
    /* ------------------------------------------------------------------ */

    private void migrateV2toV3() {
        backup(2);
        Path overrides = dataDir.resolve(ConfigStore.STATE_DIR).resolve(ConfigStore.OVERRIDES_FILE);
        try {
            if (!Files.isRegularFile(overrides)) {
                Files.createDirectories(overrides.getParent());
                Files.writeString(overrides,
                        "# Machine-owned configuration overlay — written by the in-game GUI.\n"
                                + "# Human config files are never rewritten; this file wins where set.\n",
                        StandardCharsets.UTF_8);
            }
        } catch (IOException failure) {
            log.accept("Could not create state/overrides.yml: " + failure);
        }
        stampVersion(3);
        log.accept("Migrated the config to version 3 (backup: config-backup-v2/;"
                + " added state/overrides.yml).");
    }

    /** Stamps {@code config-version} textually so the human file keeps its comments. */
    private void stampVersion(int version) {
        Path main = dataDir.resolve(ConfigStore.MAIN_FILE);
        try {
            String text = Files.readString(main, StandardCharsets.UTF_8);
            String stamped;
            if (text.matches("(?s).*(^|\\n)config-version:.*")) {
                stamped = text.replaceFirst("(?m)^config-version:.*$", "config-version: " + version);
            } else {
                stamped = "config-version: " + version + "\n" + text;
            }
            Files.writeString(main, stamped, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            log.accept("Could not stamp config-version " + version + ": " + failure);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Helpers                                                            */
    /* ------------------------------------------------------------------ */

    /** Copies config.yml into {@code config-backup-v<version>/} before a step. */
    private void backup(int version) {
        Path backupDir = dataDir.resolve("config-backup-v" + version);
        Path main = dataDir.resolve(ConfigStore.MAIN_FILE);
        try {
            Files.createDirectories(backupDir);
            if (Files.isRegularFile(main)) {
                Files.copy(main, backupDir.resolve(ConfigStore.MAIN_FILE),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            log.accept("Could not back up config.yml before the v" + version + " migration: " + failure);
        }
    }

    private static void copyLeaves(
            ConfigurationSection section, String prefix, Map<String, Object> values, String... skipKeys) {
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

    private void writeFromTemplate(String resource, Path target, Map<String, Object> values) {
        String template = readResource(resource);
        if (template == null) {
            log.accept("Bundled template " + resource + " is missing from the jar");
            return;
        }
        try {
            if (values.isEmpty()) {
                Files.writeString(target, template, StandardCharsets.UTF_8);
                return;
            }
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new StringReader(template));
            values.forEach(yaml::set);
            Files.writeString(target, yaml.saveToString(), StandardCharsets.UTF_8);
        } catch (IOException | InvalidConfigurationException failure) {
            log.accept("Could not write " + target.getFileName() + " during migration: " + failure);
        }
    }

    private YamlConfiguration loadMain() {
        YamlConfiguration yaml = new YamlConfiguration();
        Path main = dataDir.resolve(ConfigStore.MAIN_FILE);
        if (!Files.isRegularFile(main)) {
            return yaml;
        }
        try {
            yaml.load(new StringReader(Files.readString(main, StandardCharsets.UTF_8)));
        } catch (IOException | InvalidConfigurationException failure) {
            log.accept("config.yml could not be read for migration: " + failure.getMessage());
        }
        return yaml;
    }

    private String readResource(String name) {
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
}
