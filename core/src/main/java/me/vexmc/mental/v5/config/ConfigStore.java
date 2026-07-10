package me.vexmc.mental.v5.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.kernel.profile.Presets;
import me.vexmc.mental.kernel.profile.SupersededPresets;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.InvalidConfigurationException;
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
    public static final String PROFILES_DIR = "profiles";
    public static final String STATE_DIR = "state";
    public static final String OVERRIDES_FILE = "overrides.yml";

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
            "modern-vanilla", "modern-uplift", "modern-combo", "custom");

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
            Map<String, Configuration> profiles) {}

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
        Path profilesDir = dataDir.resolve(PROFILES_DIR);
        if (!Files.isDirectory(profilesDir) && !mkdirs(profilesDir)) {
            log.accept("Could not create " + profilesDir + " — profiles unavailable");
            return;
        }
        for (String preset : BUNDLED_PROFILES) {
            String folder = bundledFolder(preset);
            Path file = profilesDir.resolve(folder).resolve(preset + ".yml");
            extractIfMissing(PROFILES_DIR + "/" + folder + "/" + preset + ".yml", file);
            ensureDeliverySection(preset, file);
            upgradeSupersededPreset(preset, file);
        }
    }

    /**
     * Replaces a preset file whose RAW BYTES still match a superseded shipped
     * revision — the owner never touched it, so only research corrections
     * separate it from the current bundle. Matching on bytes (not parsed values,
     * as before 2.4.9) is what makes owner edits sacred: value matching reverted
     * an edit that landed on old values, and the bundled files' own comment
     * invites exactly one ("Restore -3.9 to unfloor") — such an edit is never
     * byte-identical to any archived bundle, so it now freezes correctly. It is
     * also parser-drift-proof. This runs AFTER {@link #ensureDeliverySection}, so
     * a pre-1.4.0 file has already had its {@code delivery} block inserted and the
     * archived hashes for those forms are the patched text (see
     * {@link SupersededPresets}).
     */
    private void upgradeSupersededPreset(String preset, Path file) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        String onDisk;
        try {
            onDisk = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            log.accept("Could not read profiles/" + preset + ".yml: " + failure);
            return;
        }
        if (!SupersededPresets.isSupersededBundleText(preset, onDisk)) {
            return;
        }
        String resource = PROFILES_DIR + "/" + bundledFolder(preset) + "/" + preset + ".yml";
        String current = readResource(resource);
        if (current == null) {
            log.accept("Bundled resource " + resource + " is missing from the jar");
            return;
        }
        try {
            Files.writeString(file, current, StandardCharsets.UTF_8);
            log.accept("profiles/" + preset + ".yml is a superseded bundled revision,"
                    + " byte-identical and unedited — upgraded to the corrected bundle"
                    + " (delete the file to regenerate anytime)");
        } catch (IOException failure) {
            log.accept("Could not upgrade profiles/" + preset + ".yml: " + failure);
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
            try (var stream = Files.walk(profilesDir)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString()
                                .toLowerCase(Locale.ROOT).endsWith(".yml"))
                        .forEach(path -> {
                            String fileName = path.getFileName().toString();
                            String stem = fileName.substring(0, fileName.length() - 4);
                            profiles.put(stem, loadYaml(path,
                                    PROFILES_DIR + "/" + profilesDir.relativize(path)));
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
                profiles);
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
