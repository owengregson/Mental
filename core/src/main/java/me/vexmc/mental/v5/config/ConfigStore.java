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
import me.vexmc.mental.kernel.profile.SupersededPresets;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * File layout and default extraction (the migration chain lives in
 * {@link Migrations}). Bundled files are extracted only when missing — owner
 * edits belong to the server and survive every restart; deleting a preset
 * regenerates the original. A preset file whose parsed values still match a
 * superseded bundled revision ({@link SupersededPresets}) was never tuned and
 * is upgraded in place to the corrected bundle; any value difference is an
 * owner edit and the file is frozen.
 *
 * <p>Ported from the retired {@code config.ConfigStore} — {@link Path} instead
 * of {@code File}, and reading the frozen kernel {@code SupersededPresets} /
 * v5 {@code ProfileParser}. The resource loader and log sink are injectable so
 * the pins port faithfully; the plain {@link #ConfigStore(Path)} uses the
 * classpath and a silent log.</p>
 */
public final class ConfigStore {

    public static final String MAIN_FILE = "config.yml";
    public static final String KNOCKBACK_FILE = "knockback.yml";
    public static final String HIT_REG_FILE = "hit-registration.yml";
    public static final String LATENCY_FILE = "latency-compensation.yml";
    public static final String PROFILES_DIR = "profiles";
    public static final String STATE_DIR = "state";
    public static final String OVERRIDES_FILE = "overrides.yml";

    /** Presets shipped in the jar; regenerated individually when missing. */
    public static final List<String> BUNDLED_PROFILES = List.of(
            "legacy-1.7", "legacy-1.8", "kohi", "minehq", "badlion", "velt",
            "mmc", "lunar", "signature", "custom");

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
            Path file = profilesDir.resolve(preset + ".yml");
            extractIfMissing(PROFILES_DIR + "/" + preset + ".yml", file);
            ensureDeliverySection(preset, file);
            upgradeSupersededPreset(preset, file);
        }
    }

    /**
     * Replaces a preset file whose parsed values still match a superseded
     * shipped revision verbatim — the owner never tuned it, so only research
     * corrections separate it from the current bundle. Any value difference is
     * an owner edit and the file is untouched.
     */
    private void upgradeSupersededPreset(String preset, Path file) {
        if (SupersededPresets.of(preset).isEmpty() || !Files.isRegularFile(file)) {
            return;
        }
        ConfigurationSection yaml = loadYaml(file, PROFILES_DIR + "/" + preset + ".yml");
        KnockbackProfile parsed = ProfileParser.parse(
                preset,
                yaml.getString("display-name", preset),
                yaml.getString("description", ""),
                new ConfigReader(yaml.getConfigurationSection("knockback"), "", new ConfigIssues()));
        if (!SupersededPresets.isSupersededVerbatim(preset, parsed)) {
            return;
        }
        String current = readResource(PROFILES_DIR + "/" + preset + ".yml");
        if (current == null) {
            log.accept("Bundled resource " + PROFILES_DIR + "/" + preset + ".yml is missing from the jar");
            return;
        }
        try {
            Files.writeString(file, current, StandardCharsets.UTF_8);
            log.accept("profiles/" + preset + ".yml carried a superseded bundled revision"
                    + " unedited — upgraded to the corrected values"
                    + " (research 2026-06-12; delete the file to regenerate anytime)");
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
            try (var stream = Files.list(profilesDir)) {
                stream.filter(path -> path.getFileName().toString()
                                .toLowerCase(Locale.ROOT).endsWith(".yml"))
                        .forEach(path -> {
                            String fileName = path.getFileName().toString();
                            String stem = fileName.substring(0, fileName.length() - 4);
                            profiles.put(stem, loadYaml(path, PROFILES_DIR + "/" + fileName));
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
