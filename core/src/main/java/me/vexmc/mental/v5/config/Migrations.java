package me.vexmc.mental.v5.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
 *       into {@code config-backup-v3/effects/} — one loud line per action. The
 *       resolution reads FROZEN copies of the 2.5.2 effective values (inlined
 *       below as historical constants — they must never track the live
 *       preset), and the import writes the 2.5.5
 *       {@code low-health-threshold-percent} key (a machine-write under the new
 *       schema, so no legacy-key warn debt).</li>
 *   <li>{@code 4 → 5}: the vanilla-preset retirement (2.5.5). The vanilla feel
 *       is "disable the Combat Effects modules" now, so the bundled
 *       {@code vanilla.yml} leaves the library: a byte-pristine copy is backed
 *       up to {@code config-backup-v4/} and deleted (an edited copy stays, and
 *       stays selectable via directory discovery); when the pristine file was
 *       removed or was never present, {@code effects.yml}'s
 *       {@code preset: vanilla} is textually flipped to {@code preset: signature}
 *       (the new default) and a machine-overlay {@code effects.preset: vanilla}
 *       is scrubbed — an edited vanilla.yml keeps its selection. The runtime
 *       unknown-name loud fallback (→ signature) is the safety net either
 *       way.</li>
 * </ul>
 *
 * <p>Each step backs up into {@code config-backup-v<N>/} first and is
 * idempotent — running the chain on an already-migrated tree is a no-op.</p>
 */
public final class Migrations {

    public static final int CURRENT_VERSION = 5;

    /**
     * The 2.5.3/2.5.4 shipped {@code effects/presets/vanilla.yml}, newline-
     * normalized SHA-256 — the pristine-delete pin for the 4 → 5 retirement.
     * A vanilla.yml whose bytes hash to this was never tuned by the owner, so
     * it is the old bundle verbatim and is safe to retire; any other bytes are
     * an owner edit and stay. Kept here (not in {@link SupersededEffectsPresets})
     * because vanilla is retired, not upgraded — the upgrade driver never visits
     * it.
     */
    private static final String PRISTINE_VANILLA_HASH =
            "7c32bf6adec4bd64319998f3e792a6f901eb78f0b20970fe494a524588560e83";

    /* --------------------------------------------------------------------- */
    /*  Frozen 2.5.2 effective values (the 3 → 4 import resolves these).      */
    /*  Historical constants — they must NEVER track the live signature      */
    /*  preset (the bundled YAML is that tune's source of truth now, and it   */
    /*  may be re-tuned; the 3 → 4 import must reproduce exactly what 2.5.2   */
    /*  shipped).                                                             */
    /* --------------------------------------------------------------------- */

    private static final List<HitFeedbackSettings.SoundSpec> FROZEN_SIGNATURE_HIT_SOUNDS = List.of(
            new HitFeedbackSettings.SoundSpec("block.lodestone.break", 1.0f, 1.0f),
            new HitFeedbackSettings.SoundSpec("entity.generic.hurt", 0.85f, 0.75f),
            new HitFeedbackSettings.SoundSpec("entity.breeze.deflect", 0.75f, 1.15f));

    private static final List<HitFeedbackSettings.ParticleSpec> FROZEN_SIGNATURE_HIT_PARTICLES = List.of(
            new HitFeedbackSettings.ParticleSpec("block", "redstone_block", 6, 8,
                    HitFeedbackSettings.Mode.EMANATE, 0.15f, 0, 0, 0));

    private static final List<HitFeedbackSettings.SoundSpec> FROZEN_SIGNATURE_LOW_HEALTH_SOUNDS =
            List.of(new HitFeedbackSettings.SoundSpec("entity.glow_squid.hurt", 0.9f, 1.2f));

    private static final List<HitFeedbackSettings.SoundSpec> FROZEN_VANILLA_HIT_SOUNDS =
            List.of(new HitFeedbackSettings.SoundSpec("entity.player.hurt", 1.0f, 1.0f));

    private static final List<HitFeedbackSettings.SoundSpec> FROZEN_SIGNATURE_DEATH_SOUNDS =
            List.of(new HitFeedbackSettings.SoundSpec("entity.glow_squid.death", 1.0f, 0.95f));

    private static final List<Integer> FROZEN_SIGNATURE_FIREWORK_COLORS =
            List.of(0xFFFFFF, 0xFFFF55, 0xFFAA00);

    /** The 2.5.2 default low-health threshold, in HEARTS — the read fallback for the import. */
    private static final double FROZEN_2_5_2_THRESHOLD_HEARTS = 4.0;

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
        if (version < 5 && Files.isRegularFile(dataDir.resolve(ConfigStore.MAIN_FILE))) {
            migrateV4toV5();
            steps.add(5);
            version = 5;
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
     * is skipped (an owner custom.yml already exists — sacred), the template's
     * signature default stays selected (since 2.5.5 retired the vanilla
     * preset), because selecting unknown custom values could change the
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
        // The 2.5.2 threshold was absolute HEARTS; 2.5.5 stores percent-of-max.
        // Convert at import (hearts of a 20-max player as percent = hearts × 10)
        // so the imported custom.yml carries the new key and never warns on
        // parse (the B10 no-silent-drops rule, settled at write time).
        double hearts = reader.numberClamped("low-health-threshold-hearts",
                FROZEN_2_5_2_THRESHOLD_HEARTS, 0.0, 100.0);
        double percent = Math.max(0.0, Math.min(100.0, hearts * 10.0));
        return switch (legacyPreset(section)) {
            case "signature" -> new HitFeedbackSettings(
                    FROZEN_SIGNATURE_HIT_SOUNDS,
                    FROZEN_SIGNATURE_HIT_PARTICLES,
                    FROZEN_SIGNATURE_LOW_HEALTH_SOUNDS,
                    percent);
            case "custom" -> new HitFeedbackSettings(
                    EffectsPresetParser.parseSounds(reader, "sounds", List.of()),
                    EffectsPresetParser.parseParticles(reader, List.of()),
                    EffectsPresetParser.parseSounds(reader, "low-health-sounds", List.of()),
                    percent);
            default -> new HitFeedbackSettings(
                    FROZEN_VANILLA_HIT_SOUNDS, List.of(), List.of(), percent);
        };
    }

    /** The death-effects twin of {@link #resolveOldHitFeedback}. */
    private DeathEffectsSettings resolveOldDeathEffects(ConfigurationSection section) {
        ConfigReader reader = new ConfigReader(section, "effects/death-effects.yml (2.5.2)",
                new ConfigIssues());
        return switch (legacyPreset(section)) {
            case "signature" -> new DeathEffectsSettings(
                    true,
                    FROZEN_SIGNATURE_DEATH_SOUNDS,
                    List.of(),
                    FROZEN_SIGNATURE_FIREWORK_COLORS,
                    // The 2.5.2 tune predates kill-title (2.7.0); the imported
                    // values carry none — the new title arrives only on a fresh
                    // signature selection or the pristine-upgrade path.
                    DeathEffectsSettings.KillTitle.NONE);
            case "custom" -> new DeathEffectsSettings(
                    reader.flag("lightning", false),
                    EffectsPresetParser.parseSounds(reader, "sounds", List.of()),
                    EffectsPresetParser.parseParticles(reader, List.of()),
                    EffectsPresetParser.parseFireworkColors(reader, List.of()),
                    EffectsPresetParser.parseKillTitle(reader, DeathEffectsSettings.KillTitle.NONE));
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
        yaml.set("hit-feedback.low-health-threshold-percent", hitFeedback.lowHealthThresholdPercent());
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
        yaml.set("damage-indicators.roll-hold-ticks", indicators.rollHoldTicks());
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
     * The template's default selection is {@code signature} (2.5.5), so the flip
     * substitutes that line; when the import was skipped the default signature
     * selection stands (the retired vanilla is gone — the 4 → 5 step then finds
     * nothing to flip).
     */
    private void writeEffectsSelection(Path effectsFile, boolean selectCustom) {
        String template = readResource(ConfigStore.EFFECTS_FILE);
        if (template == null) {
            log.accept("Bundled template " + ConfigStore.EFFECTS_FILE + " is missing from the jar");
            return;
        }
        String content = selectCustom
                ? template.replaceFirst("(?m)^  preset: signature$", "  preset: custom")
                : template;
        try {
            Files.writeString(effectsFile, content, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            log.accept("Could not write " + ConfigStore.EFFECTS_FILE + " during migration: " + failure);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  4 -> 5 : retire the bundled vanilla preset                         */
    /* ------------------------------------------------------------------ */

    /**
     * The vanilla-preset retirement (2.5.5). A byte-pristine
     * {@code effects/presets/vanilla.yml} is backed up and deleted (an edited
     * copy stays and remains selectable via directory discovery); when the
     * pristine file was removed or was never present, {@code effects.yml}'s
     * {@code preset: vanilla} is flipped to {@code preset: signature} (the new
     * default) and a machine-overlay {@code effects.preset: vanilla} is scrubbed.
     * An edited vanilla.yml keeps its selection untouched. One loud line per
     * action; the runtime unknown-name loud fallback is the safety net either
     * way.
     */
    private void migrateV4toV5() {
        retirePristineVanillaPreset();
        boolean vanillaGone = !Files.isRegularFile(
                dataDir.resolve(ConfigStore.EFFECTS_PRESETS_DIR).resolve("vanilla.yml"));
        if (vanillaGone) {
            flipEffectsSelectionVanillaToSignature();
            scrubVanillaEffectsOverlaySelection();
        }
        stampVersion(5);
    }

    /**
     * Retires a byte-pristine vanilla.yml: back it up into {@code config-backup-v4/}
     * and delete it (mirroring the 3 → 4 backup precedent). An edited copy —
     * anything whose newline-normalized bytes do not hash to the shipped
     * 2.5.3/2.5.4 bundle — is an owner file and is left in place, still selectable
     * by directory discovery.
     */
    private void retirePristineVanillaPreset() {
        Path vanillaFile = dataDir.resolve(ConfigStore.EFFECTS_PRESETS_DIR).resolve("vanilla.yml");
        if (!Files.isRegularFile(vanillaFile)) {
            return; // never present — nothing to retire
        }
        String onDisk;
        try {
            onDisk = Files.readString(vanillaFile, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            log.accept("Could not read effects/presets/vanilla.yml during the 4→5 migration: " + failure);
            return;
        }
        if (!isPristineVanilla(onDisk)) {
            log.accept("effects/presets/vanilla.yml has local edits — kept in place and still"
                    + " selectable; the bundled vanilla preset is retired in 2.5.5 (disable the"
                    + " Combat Effects modules for the vanilla feel).");
            return;
        }
        Path backupDir = dataDir.resolve("config-backup-v4").resolve(ConfigStore.EFFECTS_PRESETS_DIR);
        try {
            Files.createDirectories(backupDir);
            Files.move(vanillaFile, backupDir.resolve("vanilla.yml"),
                    StandardCopyOption.REPLACE_EXISTING);
            log.accept("Retired the pristine effects/presets/vanilla.yml (backup:"
                    + " config-backup-v4/effects/presets/) — the vanilla feel is now 'disable the"
                    + " Combat Effects modules'; the signature preset is the default.");
        } catch (IOException failure) {
            log.accept("Could not retire effects/presets/vanilla.yml: " + failure);
        }
    }

    /**
     * Textually flips {@code effects.yml}'s {@code   preset: vanilla} to
     * {@code   preset: signature} (the {@link #writeEffectsSelection}
     * replaceFirst precedent) so every comment survives. Runs only when
     * vanilla.yml is gone; a selection that is already signature/custom/other
     * never matches and is left alone.
     */
    private void flipEffectsSelectionVanillaToSignature() {
        Path effectsFile = dataDir.resolve(ConfigStore.EFFECTS_FILE);
        if (!Files.isRegularFile(effectsFile)) {
            return; // fresh tree — ConfigStore extracts the signature-default template
        }
        try {
            String text = Files.readString(effectsFile, StandardCharsets.UTF_8);
            String flipped = text.replaceFirst("(?m)^  preset: vanilla$", "  preset: signature");
            if (!flipped.equals(text)) {
                Files.writeString(effectsFile, flipped, StandardCharsets.UTF_8);
                log.accept("effects.yml selected the retired 'vanilla' preset — switched to"
                        + " 'signature' (the new default); disable the Combat Effects modules for"
                        + " the vanilla feel.");
            }
        } catch (IOException failure) {
            log.accept("Could not update effects.yml during the 4→5 migration: " + failure);
        }
    }

    /**
     * Scrubs a machine-overlay {@code effects.preset: vanilla} when vanilla.yml
     * is gone — the overlay wins over effects.yml, so a stale vanilla selection
     * there would keep steering the retired preset (the runtime would loudly
     * stand signature in on every reload). Boot-time scrubbing already drops the
     * retired per-module effects keys; this one-time selection scrub belongs to
     * the migration because it is tied to the file retirement. Loud, once.
     */
    private void scrubVanillaEffectsOverlaySelection() {
        Path overrides = dataDir.resolve(ConfigStore.STATE_DIR).resolve(ConfigStore.OVERRIDES_FILE);
        if (!Files.isRegularFile(overrides)) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(new StringReader(Files.readString(overrides, StandardCharsets.UTF_8)));
        } catch (IOException | InvalidConfigurationException failure) {
            log.accept("state/overrides.yml could not be read during the 4→5 migration: "
                    + failure.getMessage());
            return;
        }
        if (!"vanilla".equalsIgnoreCase(String.valueOf(yaml.getString("effects.preset")))) {
            return;
        }
        yaml.set("effects.preset", null);
        ConfigurationSection effects = yaml.getConfigurationSection("effects");
        if (effects != null && effects.getKeys(false).isEmpty()) {
            yaml.set("effects", null);
        }
        try {
            Files.writeString(overrides, yaml.saveToString(), StandardCharsets.UTF_8);
            log.accept("state/overrides.yml selected the retired 'vanilla' effects preset —"
                    + " removed the overlay key (signature is the default now).");
        } catch (IOException failure) {
            log.accept("Could not update state/overrides.yml during the 4→5 migration: " + failure);
        }
    }

    /** Whether {@code text} is the byte-pristine 2.5.3/2.5.4 vanilla.yml (newline-normalized). */
    private static boolean isPristineVanilla(String text) {
        if (text == null) {
            return false;
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        return PRISTINE_VANILLA_HASH.equals(sha256Hex(normalized));
    }

    /** SHA-256 hex, mirroring {@link SupersededEffectsPresets}'s normalization + encoding. */
    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
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
