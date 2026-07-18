package me.vexmc.mental.v5.config;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

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

    // The overridden keys as an immutable snapshot, refreshed on every mutation.
    // Overlay mutations happen on the global thread (reload / GUI writes), but
    // has(...) is read from viewers' region threads during draw(); a volatile
    // immutable view is the same publish-don't-share discipline as PlayerView —
    // readers never touch the LinkedHashMap the writer is mutating.
    private volatile Set<String> keyView = Set.of();

    public Overlay(Path stateFile) {
        this.stateFile = stateFile;
        load();
    }

    /** The overridden keys, in insertion order (for the GUI's "modified" marks). */
    public Map<String, Object> overrides() {
        return Map.copyOf(overrides);
    }

    /**
     * The GUI's "is this knob overridden in-GUI?" probe — true when {@code key}
     * has a machine-overlay value winning over the human file. Read-only and
     * additive; it powers the settings screens' ⚑ marker and the Q-to-reset
     * affordance ({@code Management.clearOverlay} only makes sense when this holds).
     */
    public boolean has(@NotNull String key) {
        return keyView.contains(key);
    }

    /**
     * Sets every override onto the matching in-memory root, routed by the first
     * path segment. The roots are the caller's parsed copies of the human files;
     * the files on disk are never touched.
     */
    public void apply(ConfigStore.Sources sources) {
        for (Map.Entry<String, Object> entry : overrides.entrySet()) {
            route(entry.getKey(), sources).set(entry.getKey(), entry.getValue());
        }
    }

    private static Configuration route(String key, ConfigStore.Sources sources) {
        String section = key.indexOf('.') >= 0 ? key.substring(0, key.indexOf('.')) : key;
        return switch (section) {
            case "knockback", "fishing-knockback", "projectile-knockback" -> sources.knockback();
            case "hit-registration" -> sources.hitReg();
            case "latency-compensation" -> sources.latency();
            // The 2.5.2 per-concern splits route to their split root — UNLESS the
            // split file never carried the section and config.yml still does, in
            // which case the write lands on config.yml (the shadow trap; see
            // splitOrHonouredMain).
            case "combo-hold", "combo-reach-handicap" ->
                    splitOrHonouredMain(sources.combo(), sources.main(), section);
            case "pot-fill", "fast-pots" ->
                    splitOrHonouredMain(sources.pots(), sources.main(), section);
            case "disable-offhand", "disable-crafting" ->
                    splitOrHonouredMain(sources.loadout(), sources.main(), section);
            // The 2.7.0 Loot Protection tunables (seconds, glow-color): an
            // in-GUI edit rides the drop-protection.yml root, winning over the
            // file exactly as every other split override does.
            case "drop-protection" -> sources.dropProtection();
            // The 2.5.3 Combat Effects selection: effects.preset (the GUI's
            // preset picker and the tester's staging key) rides the effects.yml
            // root the parser reads the selection from. The three retired
            // per-module prefixes are scrubbed from the overlay at boot with a
            // loud line each, so nothing routes them anymore.
            case "effects" -> sources.effects();
            default -> sources.main(); // modules, anticheat, metrics, debug
        };
    }

    /**
     * Routes a split-managed section's override to the root the parser actually
     * reads. Normally that is the split root, so the override wins over the split
     * FILE exactly as it wins over config.yml (effective = overlay ?? file ??
     * default).
     *
     * <p>The shadow trap it guards against: on a pre-2.5.2 install whose split
     * file was never extracted, config.yml still carries the section and
     * {@code SnapshotParser.movedSection} HONOURS that old location. Routing a
     * lone overlay write to the EMPTY split root would materialise the section
     * there, flip movedSection to split-wins, and SILENTLY revert every other
     * value the owner had tuned in config.yml. So when the split root has no such
     * section but config.yml does, the override lands on config.yml — exactly
     * where the parser reads — and precedence never shifts behind the owner's
     * back. Once the split file carries the section, the split root wins (the
     * byte-identical common case, matching {@code movedSection}).</p>
     */
    private static Configuration splitOrHonouredMain(
            Configuration splitRoot, Configuration main, String head) {
        boolean inSplit = splitRoot.getConfigurationSection(head) != null;
        boolean inMain = main.getConfigurationSection(head) != null;
        return !inSplit && inMain ? main : splitRoot;
    }

    /** Records an override and persists the overlay file (only). */
    public void set(String key, Object value) {
        overrides.put(key, value);
        keyView = Set.copyOf(overrides.keySet());
        persist();
    }

    /** Clears an override and persists the overlay file (only). */
    public void remove(String key) {
        overrides.remove(key);
        keyView = Set.copyOf(overrides.keySet());
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
        keyView = Set.copyOf(overrides.keySet());
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
