package me.vexmc.mental.v5.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.kernel.profile.Presets;
import me.vexmc.mental.kernel.profile.SupersededPresets;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * File layout and default extraction (the migration chain lives in
 * {@link Migrations}). Bundled files are extracted only when missing — owner
 * edits belong to the server and survive every restart; deleting a preset
 * regenerates the original. A preset file whose RAW BYTES still match a
 * superseded bundled revision ({@link SupersededPresets#isSupersededBundleText})
 * was never touched and is upgraded in place to the corrected bundle; ANY
 * difference — a value, a comment, formatting — is an owner edit and the file
 * is frozen.
 *
 * <p>Ported from the retired {@code config.ConfigStore} — {@link Path} instead
 * of {@code File}, and reading the frozen kernel {@code SupersededPresets}. The
 * resource loader and log sink are injectable so the pins port faithfully; the
 * plain {@link #ConfigStore(Path)} uses the classpath and a silent log.</p>
 */
public final class ConfigStore {

    public static final String MAIN_FILE = "config.yml";
    public static final String KNOCKBACK_FILE = "knockback.yml";
    public static final String HIT_REG_FILE = "hit-registration.yml";
    public static final String LATENCY_FILE = "latency-compensation.yml";
    public static final String COMBO_FILE = "combo.yml";
    public static final String POTS_FILE = "pots.yml";
    public static final String LOADOUT_FILE = "loadout.yml";
    public static final String DROP_PROTECTION_FILE = "drop-protection.yml";
    public static final String EFFECTS_DIR = "effects";
    /** The Combat Effects selection file — {@code knockback.yml}'s role for the FEEDBACK family. */
    public static final String EFFECTS_FILE = "effects.yml";
    /** One Combat Effects preset per file, the profiles/ model mirrored. */
    public static final String EFFECTS_PRESETS_DIR = EFFECTS_DIR + "/presets";
    public static final String PROFILES_DIR = "profiles";
    /** One rules bundle per file — the whole-ruleset macro library (2.8.x). */
    public static final String BUNDLES_DIR = "bundles";
    public static final String STATE_DIR = "state";
    public static final String OVERRIDES_FILE = "overrides.yml";

    /**
     * The 2.5.2 per-module effects files, retired by the 2.5.3 preset library.
     * {@link Migrations} imports their resolved effective values into
     * {@code effects/presets/custom.yml} and backs them up; nothing loads them
     * anymore.
     */
    public static final String LEGACY_HIT_FEEDBACK_FILE = EFFECTS_DIR + "/hit-feedback.yml";
    public static final String LEGACY_DAMAGE_INDICATORS_FILE = EFFECTS_DIR + "/damage-indicators.yml";
    public static final String LEGACY_DEATH_EFFECTS_FILE = EFFECTS_DIR + "/death-effects.yml";
    public static final List<String> LEGACY_EFFECTS_FILES = List.of(
            LEGACY_HIT_FEEDBACK_FILE, LEGACY_DAMAGE_INDICATORS_FILE, LEGACY_DEATH_EFFECTS_FILE);

    /**
     * The config.yml section names the FEEDBACK family once parsed from (both
     * the pre-2.5.2 old-location shape and the 2.5.2 split files' sections).
     * Retired with the preset library: {@code SnapshotParser} names a lingering
     * section loudly (never honours, never silent) and {@code Migrations}
     * resolves them during the 3 → 4 import.
     */
    public static final List<String> RETIRED_EFFECTS_SECTIONS =
            List.of("hit-feedback", "damage-indicators", "death-effects");

    /**
     * The 2.5.2 per-concern splits: each file's top-level sections used to live
     * in config.yml. The map drives BOTH halves of the move's back-compat
     * contract — {@link #ensureDefaultFiles} suppresses a split file's
     * extraction while config.yml still carries one of its sections (a pristine
     * bundle must never shadow a tuned old-location section), and
     * {@code SnapshotParser} honours the old location with one loud line per
     * parse. The three 2.5.2 effects files left this map with the 2.5.3 preset
     * library — their sections are {@link #RETIRED_EFFECTS_SECTIONS} now.
     */
    public static final Map<String, List<String>> SPLIT_FILE_SECTIONS = Map.of(
            COMBO_FILE, List.of("combo-hold", "combo-reach-handicap"),
            POTS_FILE, List.of("pot-fill", "fast-pots"),
            LOADOUT_FILE, List.of("disable-offhand", "disable-crafting"));

    /**
     * The bundled Combat Effects presets; regenerated individually when
     * missing. {@code vanilla} left the bundle in 2.5.5 (the vanilla feel is
     * "disable the modules"): its pristine copy is retired by the 4 → 5
     * migration and it is never re-extracted, though a user vanilla.yml left on
     * disk stays selectable through directory discovery.
     */
    public static final List<String> BUNDLED_EFFECTS_PRESETS =
            List.of("signature", "custom");

    /** The formula-category folder for a legacy-formula profile. */
    public static final String LEGACY_FOLDER = "legacy";
    /** The formula-category folder for a modern-formula profile. */
    public static final String MODERN_FOLDER = "modern";

    /**
     * Presets shipped in the jar; regenerated individually when missing. Each
     * lives under its FORMULA-category folder ({@code profiles/legacy/} or
     * {@code profiles/modern/}) — the on-disk mirror of the GUI's formula
     * chooser. Resolution is still by name (stem), so the folder is purely
     * organisational; discovery walks the whole {@code profiles/} tree.
     */
    public static final List<String> BUNDLED_PROFILES = List.of(
            "legacy-1.7", "legacy-1.8", "kohi", "minehq", "badlion", "velt",
            "mmc", "lunar", "signature",
            "modern-vanilla", "modern-uplift", "modern-combo", "ct8c", "custom");

    /**
     * The rules bundles shipped in the jar; regenerated individually when missing,
     * on the exact profiles/effects contract (extracted only when missing, owner
     * edits sacred, deleting regenerates pristine, a byte-identical superseded
     * revision upgraded in place). Unlike profiles there is no formula folder — the
     * library is one flat directory, discovery a plain listing by stem. {@code ct8c}
     * turns on the full Combat Test 8c surface; {@code signature} turns on the
     * classic 1.7-feel rule set; {@code vanilla} turns everything off (Mental
     * transparent).
     */
    public static final List<String> BUNDLED_BUNDLES = List.of("ct8c", "signature", "vanilla");

    /**
     * The formula-category folder a bundled preset lives in — derived from the
     * preset's own formula ({@code modern} presets live under {@code modern/},
     * everything else under {@code legacy/}), so the folder can never disagree
     * with the file's {@code formula:}. Unknown names default to the legacy
     * folder (the migrated {@code custom} and any hand-added preset).
     */
    public static String bundledFolder(String preset) {
        KnockbackProfile profile = Presets.ALL.get(preset);
        return profile != null && profile.modern().enabled() ? MODERN_FOLDER : LEGACY_FOLDER;
    }

    /** Every file loaded into typed configuration roots for {@link SnapshotParser}. */
    public record Sources(
            Configuration main,
            Configuration knockback,
            Configuration hitReg,
            Configuration latency,
            Configuration combo,
            Configuration pots,
            Configuration loadout,
            Configuration dropProtection,
            Configuration effects,
            Map<String, Configuration> effectsPresets,
            Map<String, Configuration> profiles) {

        /**
         * The pre-2.5.2 four-root shape with every split file empty and no
         * Combat Effects sources — the parser then resolves each split section
         * to its config.yml fallback (or the defaults) and the effects
         * selection to the in-code vanilla preset. Test seam; production
         * always loads the full set.
         */
        public static Sources of(
                Configuration main,
                Configuration knockback,
                Configuration hitReg,
                Configuration latency,
                Map<String, Configuration> profiles) {
            return new Sources(main, knockback, hitReg, latency,
                    new MemoryConfiguration(), new MemoryConfiguration(), new MemoryConfiguration(),
                    new MemoryConfiguration(), new MemoryConfiguration(), Map.of(),
                    profiles);
        }
    }

    private final Path dataDir;
    private final Function<String, InputStream> resources;
    private final Consumer<String> log;

    public ConfigStore(Path dataDir, Function<String, InputStream> resources, Consumer<String> log) {
        this.dataDir = dataDir;
        this.resources = resources;
        this.log = log;
    }

    /** Classpath-backed store with a silent log — the production wiring shape. */
    public ConfigStore(Path dataDir) {
        this(dataDir, name -> ConfigStore.class.getClassLoader().getResourceAsStream(name), message -> {});
    }

    /** The absolute path to the machine-owned overlay file. */
    public Path overridesFile() {
        return dataDir.resolve(STATE_DIR).resolve(OVERRIDES_FILE);
    }

    /** Extracts every missing bundled file; never touches an existing one. */
    public void ensureDefaultFiles() {
        extractIfMissing(MAIN_FILE, dataDir.resolve(MAIN_FILE));
        extractIfMissing(KNOCKBACK_FILE, dataDir.resolve(KNOCKBACK_FILE));
        extractIfMissing(HIT_REG_FILE, dataDir.resolve(HIT_REG_FILE));
        extractIfMissing(LATENCY_FILE, dataDir.resolve(LATENCY_FILE));
        // drop-protection.yml (2.7.0) is a brand-new concern file with no old
        // config.yml location, so it extracts unconditionally when missing.
        extractIfMissing(DROP_PROTECTION_FILE, dataDir.resolve(DROP_PROTECTION_FILE));
        // The 2.5.2 per-concern splits extract like everything else — only when
        // missing — with ONE guard: while config.yml still carries a section
        // that moved into the file, extraction is suppressed so a pristine
        // bundle can never shadow the owner's tuned old-location section. The
        // suppression itself is silent; the parser's per-reload issue line is
        // THE loud notice (mandate B10: loud, once — not twice). Deleting the
        // section from config.yml lets the bundle extract on the next boot.
        Configuration mainOnDisk = loadYaml(dataDir.resolve(MAIN_FILE), MAIN_FILE);
        for (Map.Entry<String, List<String>> split : SPLIT_FILE_SECTIONS.entrySet()) {
            boolean legacyLocationInUse = split.getValue().stream()
                    .anyMatch(section -> mainOnDisk.getConfigurationSection(section) != null);
            if (!legacyLocationInUse) {
                extractIfMissing(split.getKey(), dataDir.resolve(split.getKey()));
            }
        }
        ensureEffectsPresetLibrary(mainOnDisk);
        Path profilesDir = dataDir.resolve(PROFILES_DIR);
        if (!Files.isDirectory(profilesDir) && !mkdirs(profilesDir)) {
            log.accept("Could not create " + profilesDir + " — profiles unavailable");
            return;
        }
        for (String preset : BUNDLED_PROFILES) {
            String folder = bundledFolder(preset);
            // Prefer a pre-folder FLAT file if one is already present: an install that
            // predates the formula folders keeps its (possibly edited) flat file in
            // place — patched and upgraded there — so extraction never creates a
            // second copy under the folder (a duplicate stem). Fresh installs land
            // in the formula-category folder.
            Path flat = profilesDir.resolve(preset + ".yml");
            Path file = Files.isRegularFile(flat)
                    ? flat
                    : profilesDir.resolve(folder).resolve(preset + ".yml");
            extractIfMissing(PROFILES_DIR + "/" + folder + "/" + preset + ".yml", file);
            ensureDeliverySection(preset, file);
            upgradeIfSupersededBundle(
                    PROFILES_DIR + "/" + preset + ".yml", file,
                    PROFILES_DIR + "/" + bundledFolder(preset) + "/" + preset + ".yml",
                    SupersededPresets::isSupersededBundleText, preset);
        }
        ensureBundleLibrary();
    }

    /**
     * The rules-bundle library (2.8.x): one file per bundled bundle under
     * {@code bundles/}, each on the extracted-only-when-missing contract with the
     * {@link SupersededBundles} pristine upgrade — the exact profiles/effects
     * shape, minus the formula folder (bundles are one flat directory) and the
     * delivery-section back-fill (bundles have no such legacy). The archive is
     * empty at 2.8.x, so no bundle upgrades yet; the wiring is in place for the
     * first corrected revision.
     */
    private void ensureBundleLibrary() {
        for (String bundle : BUNDLED_BUNDLES) {
            Path file = dataDir.resolve(BUNDLES_DIR).resolve(bundle + ".yml");
            extractIfMissing(BUNDLES_DIR + "/" + bundle + ".yml", file);
            upgradeIfSupersededBundle(
                    BUNDLES_DIR + "/" + bundle + ".yml", file,
                    BUNDLES_DIR + "/" + bundle + ".yml",
                    SupersededBundles::isSupersededBundleText, bundle);
        }
    }

    /**
     * The Combat Effects preset library (2.5.3): {@code effects.yml} plus one
     * file per bundled preset under {@code effects/presets/}, each on the
     * extracted-only-when-missing contract, with the SupersededPresets-style
     * pristine upgrade for {@code vanilla}/{@code signature} (never
     * {@code custom} — it is the owner's preset by definition). One guard: a
     * 2.5.2 tree that still awaits the 3 → 4 migration (config-version below 4
     * with the old per-module files or config.yml sections present) must not
     * see {@code effects.yml} or {@code custom.yml} extracted — the migration
     * owns creating both ({@code custom.yml}'s first extraction IS the import
     * of the old effective values, and {@code effects.yml} must come up with
     * custom selected). The suppression is silent; the migration prints the
     * loud lines (mandate B10: loud, once).
     */
    private void ensureEffectsPresetLibrary(Configuration mainOnDisk) {
        boolean awaitingEffectsMigration = mainOnDisk.getInt("config-version", 0) < 4
                && (LEGACY_EFFECTS_FILES.stream()
                        .anyMatch(file -> Files.isRegularFile(dataDir.resolve(file)))
                || RETIRED_EFFECTS_SECTIONS.stream()
                        .anyMatch(section -> mainOnDisk.getConfigurationSection(section) != null));
        if (!awaitingEffectsMigration) {
            extractIfMissing(EFFECTS_FILE, dataDir.resolve(EFFECTS_FILE));
        }
        for (String preset : BUNDLED_EFFECTS_PRESETS) {
            boolean custom = "custom".equals(preset);
            if (custom && awaitingEffectsMigration) {
                continue;
            }
            Path file = dataDir.resolve(EFFECTS_PRESETS_DIR).resolve(preset + ".yml");
            extractIfMissing(EFFECTS_PRESETS_DIR + "/" + preset + ".yml", file);
            if (!custom) {
                upgradeIfSupersededBundle(
                        EFFECTS_PRESETS_DIR + "/" + preset + ".yml", file,
                        EFFECTS_PRESETS_DIR + "/" + preset + ".yml",
                        SupersededEffectsPresets::isSupersededBundleText, preset);
            }
        }
    }

    /**
     * Replaces a preset file whose RAW BYTES still match a superseded shipped
     * revision — the owner never touched it, so only research corrections separate
     * it from the current bundle. Matching on bytes (not parsed values, as before
     * 2.4.9) is what makes owner edits sacred and parser drift irrelevant. One
     * driver serves both preset kinds: the archive predicate is the only thing
     * that differs (kernel SupersededPresets for knockback, core
     * SupersededEffectsPresets for effects). For knockback this runs AFTER
     * {@link #ensureDeliverySection}, so a pre-1.4.0 file has already had its
     * {@code delivery} block inserted and the archived hashes for those forms are
     * the patched text (see SupersededPresets).
     */
    private void upgradeIfSupersededBundle(
            String label, Path file, String resource,
            BiPredicate<String, String> archive, String preset) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        String onDisk;
        try {
            onDisk = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            log.accept("Could not read " + label + ": " + failure);
            return;
        }
        if (!archive.test(preset, onDisk)) {
            return;
        }
        String current = readResource(resource);
        if (current == null) {
            log.accept("Bundled resource " + resource + " is missing from the jar");
            return;
        }
        try {
            Files.writeString(file, current, StandardCharsets.UTF_8);
            log.accept(label + " is a superseded bundled revision,"
                    + " byte-identical and unedited — upgraded to the corrected bundle"
                    + " (delete the file to regenerate anytime)");
        } catch (IOException failure) {
            log.accept("Could not upgrade " + label + ": " + failure);
        }
    }

    /**
     * 1.4.0 introduced the per-profile {@code delivery} block; a preset file
     * from an earlier install lacks it and would parse to the legacy defaults.
     * Bundled presets that predate the knob get exactly the missing section
     * inserted; every user-edited value stays untouched (custom is never
     * modified).
     */
    private void ensureDeliverySection(String preset, Path file) {
        String melee = switch (preset) {
            case "legacy-1.8", "mmc" -> "immediate";
            default -> "tracker";
        };
        String projectile = "mmc".equals(preset) ? "immediate" : "tracker";
        if ("custom".equals(preset) || !Files.exists(file)) {
            return;
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.contains("delivery:")) {
                return;
            }
            String block = "  delivery:\n    melee: " + melee
                    + "\n    projectile: " + projectile + "\n";
            int anchor = content.indexOf("  modifiers:");
            String updated = anchor >= 0
                    ? content.substring(0, anchor) + block + content.substring(anchor)
                    : content + "\n" + block;
            Files.writeString(file, updated, StandardCharsets.UTF_8);
            log.accept("Added the 1.4.0 delivery section to profiles/" + preset + ".yml");
        } catch (IOException failure) {
            log.accept("Could not update profiles/" + preset + ".yml: " + failure);
        }
    }

    /**
     * Loads every file into one {@link Sources}. A file that fails to parse is
     * reported and treated as empty (defaults). The overlay is applied by the
     * caller over these in-memory roots — the human files on disk are never
     * rewritten.
     */
    public Sources loadSources() {
        Map<String, Configuration> profiles = new TreeMap<>();
        Path profilesDir = dataDir.resolve(PROFILES_DIR);
        if (Files.isDirectory(profilesDir)) {
            // Walk the whole profiles/ tree: profiles live under a formula-category
            // folder (profiles/legacy/, profiles/modern/), but resolution is by name
            // (stem), so a flat file from a pre-folder install is still discovered and
            // any user subfolder works too. Keyed by stem — the folder is organisational.
            // Files.walk order is filesystem-dependent, so same-stem twins in
            // different folders would otherwise shadow each other differently
            // between boots. Sorting shallowest-first (then by path) makes the
            // winner reproducible AND lets a pre-folder flat file outrank a
            // foldered twin — the same preference extraction/upgrade apply.
            try (var stream = Files.walk(profilesDir)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString()
                                .toLowerCase(Locale.ROOT).endsWith(".yml"))
                        .sorted(Comparator.comparingInt(Path::getNameCount)
                                .thenComparing(Path::toString))
                        .forEach(path -> {
                            String fileName = path.getFileName().toString();
                            String stem = fileName.substring(0, fileName.length() - 4);
                            String relative = PROFILES_DIR + "/" + profilesDir.relativize(path);
                            if (profiles.containsKey(stem)) {
                                log.accept("Ignoring " + relative + ": profile '" + stem
                                        + "' is already loaded from another file — remove one of the two");
                                return;
                            }
                            profiles.put(stem, loadYaml(path, relative));
                        });
            } catch (IOException failure) {
                log.accept("Could not list " + profilesDir + ": " + failure);
            }
        }
        return new Sources(
                loadYaml(dataDir.resolve(MAIN_FILE), MAIN_FILE),
                loadYaml(dataDir.resolve(KNOCKBACK_FILE), KNOCKBACK_FILE),
                loadYaml(dataDir.resolve(HIT_REG_FILE), HIT_REG_FILE),
                loadYaml(dataDir.resolve(LATENCY_FILE), LATENCY_FILE),
                loadYaml(dataDir.resolve(COMBO_FILE), COMBO_FILE),
                loadYaml(dataDir.resolve(POTS_FILE), POTS_FILE),
                loadYaml(dataDir.resolve(LOADOUT_FILE), LOADOUT_FILE),
                loadYaml(dataDir.resolve(DROP_PROTECTION_FILE), DROP_PROTECTION_FILE),
                loadYaml(dataDir.resolve(EFFECTS_FILE), EFFECTS_FILE),
                loadEffectsPresets(),
                profiles);
    }

    /**
     * Every {@code effects/presets/*.yml} keyed by stem — a flat directory (no
     * category folders, unlike profiles/), so discovery is a plain sorted
     * listing; any file an owner drops in becomes selectable by its stem. An
     * unparseable preset is reported and served empty, which parses to the
     * vanilla-valued defaults — the loud fallback the selection warns about.
     *
     * <p>The {@code signature} tune is the default preset, so it must survive a
     * torn install: when its disk file is missing or parsed to nothing (deleted,
     * empty, or unreadable), the JAR RESOURCE text stands in with a loud line —
     * the signature stem is always resolvable even if the directory lost it.
     * Every OTHER stem stays purely directory-driven, so a surviving user
     * {@code vanilla.yml} remains selectable.</p>
     */
    private Map<String, Configuration> loadEffectsPresets() {
        Map<String, Configuration> presets = new TreeMap<>();
        Path presetsDir = dataDir.resolve(EFFECTS_PRESETS_DIR);
        if (Files.isDirectory(presetsDir)) {
            try (var stream = Files.list(presetsDir)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString()
                                .toLowerCase(Locale.ROOT).endsWith(".yml"))
                        .sorted()
                        .forEach(path -> {
                            String fileName = path.getFileName().toString();
                            String stem = fileName.substring(0, fileName.length() - 4);
                            presets.put(stem, loadYaml(path, EFFECTS_PRESETS_DIR + "/" + fileName));
                        });
            } catch (IOException failure) {
                log.accept("Could not list " + presetsDir + ": " + failure);
            }
        }
        Configuration onDisk = presets.get("signature");
        if (onDisk == null || onDisk.getKeys(false).isEmpty()) {
            Configuration resource = loadResourceYaml(EFFECTS_PRESETS_DIR + "/signature.yml");
            if (resource != null) {
                presets.put("signature", resource);
                log.accept("effects/presets/signature.yml is missing or unreadable on disk"
                        + " — serving the bundled signature tune from the jar");
            }
        }
        return presets;
    }

    /**
     * Every {@code bundles/*.yml} keyed by stem — a flat directory (like the
     * effects presets), so discovery is a plain sorted listing; any bundle an
     * owner drops in becomes selectable by its stem. Bundles are read straight
     * from disk with NO overlay applied — they are static reference macros, not
     * live config the overlay wins over — and an unparseable file is reported and
     * served empty (it will simply carry no modules, which {@code RulesBundleParser}
     * flags loudly). The library is read fresh on each call (boot, reload, GUI
     * open, apply); it is three tiny files, so re-reading is negligible and keeps
     * the listing honest to the disk.
     */
    public Map<String, Configuration> loadBundles() {
        Map<String, Configuration> bundles = new TreeMap<>();
        Path bundlesDir = dataDir.resolve(BUNDLES_DIR);
        if (Files.isDirectory(bundlesDir)) {
            try (var stream = Files.list(bundlesDir)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString()
                                .toLowerCase(Locale.ROOT).endsWith(".yml"))
                        .sorted()
                        .forEach(path -> {
                            String fileName = path.getFileName().toString();
                            String stem = fileName.substring(0, fileName.length() - 4);
                            bundles.put(stem, loadYaml(path, BUNDLES_DIR + "/" + fileName));
                        });
            } catch (IOException failure) {
                log.accept("Could not list " + bundlesDir + ": " + failure);
            }
        }
        return bundles;
    }

    /** The bundled YAML text of {@code resource} parsed into a configuration root, or null. */
    private Configuration loadResourceYaml(String resource) {
        String text = readResource(resource);
        if (text == null) {
            log.accept("Bundled resource " + resource + " is missing from the jar");
            return null;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(new StringReader(text));
        } catch (IOException | InvalidConfigurationException failure) {
            log.accept("Bundled resource " + resource + " could not be parsed: " + failure.getMessage());
            return null;
        }
        return yaml;
    }

    /* ------------------------------------------------------------------ */
    /*  IO helpers                                                         */
    /* ------------------------------------------------------------------ */

    private void extractIfMissing(String resource, Path target) {
        if (Files.exists(target)) {
            return;
        }
        String content = readResource(resource);
        if (content == null) {
            log.accept("Bundled resource " + resource + " is missing from the jar");
            return;
        }
        try {
            Path parent = target.getParent();
            if (parent != null && !Files.isDirectory(parent) && !mkdirs(parent)) {
                log.accept("Could not create " + parent + " for " + resource);
                return;
            }
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            log.accept("Could not extract " + resource + ": " + failure);
        }
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

    private YamlConfiguration loadYaml(Path file, String label) {
        YamlConfiguration yaml = new YamlConfiguration();
        if (!Files.isRegularFile(file)) {
            return yaml;
        }
        try (InputStreamReader reader = new InputStreamReader(
                Files.newInputStream(file), StandardCharsets.UTF_8)) {
            yaml.load(reader);
        } catch (IOException | InvalidConfigurationException failure) {
            log.accept(label + " could not be parsed — using defaults for it: " + failure.getMessage());
        }
        return yaml;
    }

    private boolean mkdirs(Path dir) {
        try {
            Files.createDirectories(dir);
            return true;
        } catch (IOException failure) {
            return false;
        }
    }
}
