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
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.v5.config.settings.DamageIndicatorsSettings;
import me.vexmc.mental.v5.config.settings.DeathEffectsSettings;
import me.vexmc.mental.v5.config.settings.HitFeedbackSettings;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * The explicit {@code config-version} migration chain (spec §10, fixing mandate
 * §2.7 "written but never read"). The version is now READ from config.yml — a
 * present stamp is authoritative; an absent one means version 1 if the file is
 * the v1 single-file shape, version 3 if the 2.5.2 per-module effects layout
 * is present, else the current version (a fresh or already-split tree needs
 * no split).
 *
 * <ul>
 *   <li>{@code 1 → 2}: the retired {@code ConfigStore.migrateLegacyLayout} —
 *       back up config.yml, split it into the per-concern files, and turn a
 *       tuned v1 knockback block into {@code profiles/custom.yml} with
 *       {@code custom} selected.</li>
 *   <li>{@code 2 → 3}: back up config.yml, create the empty
 *       {@code state/overrides.yml}, and stamp {@code config-version: 3}. No
 *       key renames.</li>
 *   <li>{@code 3 → 4}: the Combat Effects preset library (2.5.3). The 2.5.2
 *       per-module files ({@code effects/hit-feedback.yml},
 *       {@code effects/damage-indicators.yml}, {@code effects/death-effects.yml})
 *       and the honoured old-location config.yml sections are resolved to
 *       their OLD EFFECTIVE values — the per-module preset enums applied, the
 *       v1-migration precedent — and imported into
 *       {@code effects/presets/custom.yml} (only when custom.yml is missing:
 *       the import IS its extraction, never a re-serialization of a human
 *       file). {@code effects.yml} is created with {@code custom} selected so
 *       an upgraded server comes up identical-sounding, and the old files move
 *       into {@code config-backup-v3/effects/} — one loud line per action.</li>
 * </ul>
 *
 * <p>Each step backs up into {@code config-backup-v<N>/} first and is
 * idempotent — running the chain on an already-migrated tree is a no-op.</p>
 */
public final class Migrations {

    public static final int CURRENT_VERSION = 4;

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
        if (version < 4 && Files.isRegularFile(dataDir.resolve(ConfigStore.MAIN_FILE))) {
            migrateV3toV4();
            steps.add(4);
            version = 4;
        }
        return new Result(from, version, steps);
    }

    /**
     * The config-version the tree is at: present stamp, else v1 by shape, else
     * v3 when the 2.5.2 per-module effects layout is present (belt-and-braces —
     * every released 2.5.x stamps its version, but an unstamped tree carrying
     * the old effects files must never read as already-current and skip the
     * import), else current.
     */
    public int detectVersion() {
        Path main = dataDir.resolve(ConfigStore.MAIN_FILE);
        if (!Files.isRegularFile(main)) {
            return CURRENT_VERSION; // nothing on disk to migrate
        }
        YamlConfiguration config = loadMain();
        if (config.isSet("config-version")) {
            return config.getInt("config-version");
        }
        if (isLegacyLayout(config)) {
            return 1;
        }
        return hasLegacyEffectsState(config) ? 3 : CURRENT_VERSION;
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
        // The migrated custom profile is a legacy-formula profile, so it lands in
        // the legacy formula-category folder alongside the bundled legacy presets.
        Path profilesDir = dataDir.resolve(ConfigStore.PROFILES_DIR)
                .resolve(ConfigStore.bundledFolder("custom"));
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
    /*  3 -> 4 : the Combat Effects preset library                         */
    /* ------------------------------------------------------------------ */

    /** Whether any 2.5.2 effects state is on disk: the per-module files or config.yml sections. */
    private boolean hasLegacyEffectsState(ConfigurationSection main) {
        for (String file : ConfigStore.LEGACY_EFFECTS_FILES) {
            if (Files.isRegularFile(dataDir.resolve(file))) {
                return true;
            }
        }
        for (String section : ConfigStore.RETIRED_EFFECTS_SECTIONS) {
            if (main.getConfigurationSection(section) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * The Combat Effects preset-library step. With no 2.5.2 effects state on
     * disk it only advances the stamp (nothing to protect, so no backup —
     * {@code ConfigStore.ensureDefaultFiles} owns the fresh extraction).
     * Otherwise: resolve the OLD effective values, move the old files into the
     * v3 backup, import into {@code effects/presets/custom.yml} when it is
     * missing, and create {@code effects.yml} with {@code custom} selected —
     * one loud line per action. Selection follows the import: when the import
     * is skipped (an owner custom.yml already exists — sacred), vanilla stays
     * selected, because selecting unknown custom values could change the
     * server's sound silently.
     */
    private void migrateV3toV4() {
        YamlConfiguration oldMain = loadMain();
        if (hasLegacyEffectsState(oldMain)) {
            backup(3);
            importLegacyEffects(oldMain);
        }
        stampVersion(4);
    }

    private void importLegacyEffects(YamlConfiguration oldMain) {
        // Resolve BEFORE moving the files: the old effective section per module
        // is the old file's section when present, else the honoured config.yml
        // old-location section (exactly the 2.5.2 precedence).
        ConfigurationSection hitSection = legacyEffectsSection(
                ConfigStore.LEGACY_HIT_FEEDBACK_FILE, "hit-feedback", oldMain);
        ConfigurationSection indicatorsSection = legacyEffectsSection(
                ConfigStore.LEGACY_DAMAGE_INDICATORS_FILE, "damage-indicators", oldMain);
        ConfigurationSection deathSection = legacyEffectsSection(
                ConfigStore.LEGACY_DEATH_EFFECTS_FILE, "death-effects", oldMain);

        HitFeedbackSettings hitFeedback = resolveOldHitFeedback(hitSection);
        DamageIndicatorsSettings indicators = EffectsPresetParser.parseDamageIndicators(
                new ConfigReader(indicatorsSection, "effects/damage-indicators.yml (2.5.2)",
                        new ConfigIssues()));
        DeathEffectsSettings death = resolveOldDeathEffects(deathSection);

        moveLegacyEffectsFilesIntoBackup();

        boolean imported = false;
        Path custom = dataDir.resolve(ConfigStore.EFFECTS_PRESETS_DIR).resolve("custom.yml");
        if (Files.isRegularFile(custom)) {
            log.accept("effects/presets/custom.yml already exists — your 2.5.2 effects values were"
                    + " NOT imported (an existing custom preset is never overwritten); they are"
                    + " preserved in config-backup-v3/effects/ if you want them back.");
        } else {
            imported = writeImportedEffectsCustom(custom, hitFeedback, indicators, death);
        }

        Path effectsFile = dataDir.resolve(ConfigStore.EFFECTS_FILE);
        if (!Files.isRegularFile(effectsFile)) {
            writeEffectsSelection(effectsFile, imported);
        }
        for (String section : ConfigStore.RETIRED_EFFECTS_SECTIONS) {
            if (oldMain.getConfigurationSection(section) != null) {
                log.accept("config.yml: the " + section + " section was resolved into the Combat"
                        + " Effects import and is retired — delete it from config.yml.");
            }
        }
    }

    /**
     * One module's old effective section: the 2.5.2 split file's top-level
     * section when the file exists and carries it, else the honoured
     * old-location config.yml section, else null (pure defaults).
     */
    private ConfigurationSection legacyEffectsSection(
            String legacyFile, String section, YamlConfiguration oldMain) {
        Path file = dataDir.resolve(legacyFile);
        if (Files.isRegularFile(file)) {
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.load(new StringReader(Files.readString(file, StandardCharsets.UTF_8)));
                ConfigurationSection inFile = yaml.getConfigurationSection(section);
                if (inFile != null) {
                    return inFile;
                }
            } catch (IOException | InvalidConfigurationException failure) {
                log.accept(legacyFile + " could not be parsed during the effects import — using"
                        + " the config.yml section (or the defaults) for " + section + ": "
                        + failure.getMessage());
            }
        }
        return oldMain.getConfigurationSection(section);
    }

    /**
     * The retired per-module {@code preset:} enum, resolved to EFFECTIVE
     * values exactly as the 2.5.2 runtime did: {@code vanilla} played the era
     * hurt sound with no particles and no low-health layer, {@code signature}
     * the in-code signature constants, {@code custom} the file's own lists
     * (absent lists meant empty — silence, not vanilla). An unknown value
     * fell back to vanilla then, so it does here too.
     */
    private HitFeedbackSettings resolveOldHitFeedback(ConfigurationSection section) {
        ConfigReader reader = new ConfigReader(section, "effects/hit-feedback.yml (2.5.2)",
                new ConfigIssues());
        double threshold = reader.numberClamped("low-health-threshold-hearts",
                HitFeedbackSettings.DEFAULTS.lowHealthThresholdHearts(), 0.0, 100.0);
        return switch (legacyPreset(section)) {
            case "signature" -> new HitFeedbackSettings(
                    HitFeedbackSettings.SIGNATURE_SOUNDS,
                    HitFeedbackSettings.SIGNATURE_PARTICLES,
                    HitFeedbackSettings.SIGNATURE_LOW_HEALTH_SOUNDS,
                    threshold);
            case "custom" -> new HitFeedbackSettings(
                    EffectsPresetParser.parseSounds(reader, "sounds", List.of()),
                    EffectsPresetParser.parseParticles(reader, List.of()),
                    EffectsPresetParser.parseSounds(reader, "low-health-sounds", List.of()),
                    threshold);
            default -> new HitFeedbackSettings(
                    HitFeedbackSettings.VANILLA_SOUNDS, List.of(), List.of(), threshold);
        };
    }

    /** The death-effects twin of {@link #resolveOldHitFeedback}. */
    private DeathEffectsSettings resolveOldDeathEffects(ConfigurationSection section) {
        ConfigReader reader = new ConfigReader(section, "effects/death-effects.yml (2.5.2)",
                new ConfigIssues());
        return switch (legacyPreset(section)) {
            case "signature" -> new DeathEffectsSettings(
                    true,
                    DeathEffectsSettings.SIGNATURE_SOUNDS,
                    DeathEffectsSettings.SIGNATURE_PARTICLES,
                    DeathEffectsSettings.SIGNATURE_FIREWORK_COLORS);
            case "custom" -> new DeathEffectsSettings(
                    reader.flag("lightning", false),
                    EffectsPresetParser.parseSounds(reader, "sounds", List.of()),
                    EffectsPresetParser.parseParticles(reader, List.of()),
                    EffectsPresetParser.parseFireworkColors(reader, List.of()));
            default -> DeathEffectsSettings.DEFAULTS;
        };
    }

    private static String legacyPreset(ConfigurationSection section) {
        if (section == null) {
            return "vanilla";
        }
        return String.valueOf(section.getString("preset", "vanilla"))
                .trim().toLowerCase(Locale.ROOT);
    }

    /** Moves each present 2.5.2 effects file into {@code config-backup-v3/effects/}, loudly. */
    private void moveLegacyEffectsFilesIntoBackup() {
        Path backupDir = dataDir.resolve("config-backup-v3").resolve(ConfigStore.EFFECTS_DIR);
        for (String legacyFile : ConfigStore.LEGACY_EFFECTS_FILES) {
            Path file = dataDir.resolve(legacyFile);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                Files.createDirectories(backupDir);
                Files.move(file, backupDir.resolve(file.getFileName()),
                        StandardCopyOption.REPLACE_EXISTING);
                log.accept("Moved " + legacyFile + " to config-backup-v3/effects/ — Combat Effects"
                        + " are preset files now (effects/presets/, selected in effects.yml).");
            } catch (IOException failure) {
                log.accept("Could not back up " + legacyFile + ": " + failure);
            }
        }
    }

    /**
     * The import — custom.yml's first extraction, machine-written from the
     * RESOLVED old effective values (never a re-serialization of a human
     * file). The body is a plain YamlConfiguration dump under a header that
     * explains its provenance; the fully-documented template remains available
     * by deleting the file (it regenerates as the pristine signature-valued
     * bundle).
     */
    private boolean writeImportedEffectsCustom(
            Path custom,
            HitFeedbackSettings hitFeedback,
            DamageIndicatorsSettings indicators,
            DeathEffectsSettings death) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("display-name", "Custom");
        yaml.set("description", "Imported from the 2.5.2 per-module effects configuration.");
        yaml.set("hit-feedback.sounds", soundMaps(hitFeedback.sounds()));
        yaml.set("hit-feedback.particles", particleMaps(hitFeedback.particles()));
        yaml.set("hit-feedback.low-health-threshold-hearts", hitFeedback.lowHealthThresholdHearts());
        yaml.set("hit-feedback.low-health-sounds", soundMaps(hitFeedback.lowHealthSounds()));
        yaml.set("damage-indicators.lifetime-ticks", indicators.lifetimeTicks());
        yaml.set("damage-indicators.ring-radius", indicators.ringRadius());
        yaml.set("damage-indicators.height-jitter", indicators.heightJitter());
        yaml.set("damage-indicators.launch-vertical", indicators.launchVertical());
        yaml.set("damage-indicators.launch-outward", indicators.launchOutward());
        yaml.set("damage-indicators.gravity", indicators.gravity());
        yaml.set("damage-indicators.drag", indicators.drag());
        yaml.set("damage-indicators.text", indicators.text());
        yaml.set("damage-indicators.crit-text", indicators.critText());
        yaml.set("damage-indicators.crit-threshold-hearts", indicators.critThresholdHearts());
        yaml.set("death-effects.lightning", death.lightning());
        yaml.set("death-effects.firework.colors", hexColors(death.fireworkColors()));
        yaml.set("death-effects.sounds", soundMaps(death.sounds()));
        yaml.set("death-effects.particles", particleMaps(death.particles()));
        String header = """
                # Mental — Combat Effects preset: custom (imported)
                #
                # Written once by the 2.5.3 migration from your 2.5.2 per-module effects
                # configuration (backed up in config-backup-v3/effects/): the old preset
                # enums were resolved to their EFFECTIVE values, so this preset sounds
                # exactly like your server did before the upgrade. It is yours to edit
                # freely and is never overwritten; deleting it regenerates the pristine
                # bundled custom preset (a fully documented copy of the signature tune).
                """;
        try {
            Files.createDirectories(custom.getParent());
            Files.writeString(custom, header + yaml.saveToString(), StandardCharsets.UTF_8);
            log.accept("Imported your 2.5.2 effects values into effects/presets/custom.yml"
                    + " and selected the custom preset — the server sounds unchanged.");
            return true;
        } catch (IOException failure) {
            log.accept("Could not write the imported effects/presets/custom.yml: " + failure);
            return false;
        }
    }

    /**
     * Creates {@code effects.yml} from the bundled template, textually flipping
     * the selection to {@code custom} when the import happened — a targeted
     * substitution (the {@code stampVersion} precedent) so every comment in the
     * template survives, where a YamlConfiguration round-trip would drop them.
     */
    private void writeEffectsSelection(Path effectsFile, boolean selectCustom) {
        String template = readResource(ConfigStore.EFFECTS_FILE);
        if (template == null) {
            log.accept("Bundled template " + ConfigStore.EFFECTS_FILE + " is missing from the jar");
            return;
        }
        String content = selectCustom
                ? template.replaceFirst("(?m)^  preset: vanilla$", "  preset: custom")
                : template;
        try {
            Files.writeString(effectsFile, content, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            log.accept("Could not write " + ConfigStore.EFFECTS_FILE + " during migration: " + failure);
        }
    }

    private static List<Map<String, Object>> soundMaps(List<HitFeedbackSettings.SoundSpec> sounds) {
        List<Map<String, Object>> maps = new ArrayList<>(sounds.size());
        for (HitFeedbackSettings.SoundSpec sound : sounds) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("sound", sound.sound());
            map.put("volume", (double) sound.volume());
            map.put("pitch", (double) sound.pitch());
            maps.add(map);
        }
        return maps;
    }

    private static List<Map<String, Object>> particleMaps(List<HitFeedbackSettings.ParticleSpec> particles) {
        List<Map<String, Object>> maps = new ArrayList<>(particles.size());
        for (HitFeedbackSettings.ParticleSpec particle : particles) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("particle", particle.particle());
            if (!particle.block().isEmpty()) {
                map.put("block", particle.block());
            }
            map.put("count-min", particle.countMin());
            map.put("count-max", particle.countMax());
            map.put("mode", particle.mode().name().toLowerCase(Locale.ROOT));
            map.put("speed", (double) particle.speed());
            Map<String, Object> spread = new LinkedHashMap<>();
            spread.put("x", particle.spreadX());
            spread.put("y", particle.spreadY());
            spread.put("z", particle.spreadZ());
            map.put("spread", spread);
            maps.add(map);
        }
        return maps;
    }

    private static List<String> hexColors(List<Integer> colors) {
        List<String> hex = new ArrayList<>(colors.size());
        for (int color : colors) {
            hex.add(String.format(Locale.ROOT, "%06x", color));
        }
        return hex;
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
