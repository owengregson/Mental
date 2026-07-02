package me.vexmc.mental.v5.config;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * The machine-owned configuration overlay (spec §10): a flat
 * {@code key -> value} store the GUI writes at {@code state/overrides.yml}.
 * Effective value = overlay ?? file ?? default. The human config files are
 * never re-serialized — {@link #apply} sets each override onto the in-memory
 * {@link Configuration} roots BEFORE {@code SnapshotParser.parse}, and
 * {@link #set}/{@link #remove} persist only the overlay file.
 *
 * <p>Keys are dotted config paths (e.g. {@code modules.knockback},
 * {@code knockback.profile}); the first path segment routes the override to
 * the correct configuration root.</p>
 */
public final class Overlay {

    private final Path stateFile;
    private final Map<String, Object> overrides = new LinkedHashMap<>();

    public Overlay(Path stateFile) {
        this.stateFile = stateFile;
        load();
    }

    /** The overridden keys, in insertion order (for the GUI's "modified" marks). */
    public Map<String, Object> overrides() {
        return Map.copyOf(overrides);
    }

    /**
     * Sets every override onto the matching in-memory root, routed by the first
     * path segment. The roots are the caller's parsed copies of the human files;
     * the files on disk are never touched.
     */
    public void apply(
            Configuration main,
            Configuration knockback,
            Configuration hitReg,
            Configuration latency) {
        for (Map.Entry<String, Object> entry : overrides.entrySet()) {
            route(entry.getKey(), main, knockback, hitReg, latency).set(entry.getKey(), entry.getValue());
        }
    }

    private static Configuration route(
            String key, Configuration main, Configuration knockback,
            Configuration hitReg, Configuration latency) {
        String section = key.indexOf('.') >= 0 ? key.substring(0, key.indexOf('.')) : key;
        return switch (section) {
            case "knockback", "fishing-knockback", "projectile-knockback" -> knockback;
            case "hit-registration" -> hitReg;
            case "latency-compensation" -> latency;
            default -> main; // modules, anticheat, debug, compatibility, disable-*
        };
    }

    /** Records an override and persists the overlay file (only). */
    public void set(String key, Object value) {
        overrides.put(key, value);
        persist();
    }

    /** Clears an override and persists the overlay file (only). */
    public void remove(String key) {
        overrides.remove(key);
        persist();
    }

    private void load() {
        overrides.clear();
        if (!Files.isRegularFile(stateFile)) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStreamReader reader = new InputStreamReader(
                Files.newInputStream(stateFile), StandardCharsets.UTF_8)) {
            yaml.load(reader);
        } catch (IOException | InvalidConfigurationException failure) {
            return; // a corrupt overlay degrades to "no overrides", never throws
        }
        for (String key : yaml.getKeys(true)) {
            if (!yaml.isConfigurationSection(key)) {
                overrides.put(key, yaml.get(key));
            }
        }
    }

    private void persist() {
        YamlConfiguration yaml = new YamlConfiguration();
        overrides.forEach(yaml::set);
        try {
            Path parent = stateFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(stateFile, yaml.saveToString(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("could not persist the config overlay", failure);
        }
    }
}
