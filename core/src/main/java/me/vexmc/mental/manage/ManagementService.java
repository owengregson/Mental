package me.vexmc.mental.manage;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import me.vexmc.mental.MentalPlugin;
import me.vexmc.mental.api.event.KnockbackProfileChangeEvent;
import me.vexmc.mental.config.AnticheatMode;
import me.vexmc.mental.config.ConfigStore;
import me.vexmc.mental.config.OcmCoordination;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

/**
 * The write-back behind the management GUI.
 *
 * <p>Each method mutates exactly one configuration key, persists it, and swaps
 * the live typed snapshot atomically through {@link MentalPlugin#reloadAll()} —
 * the same path the old commands used, so a half-applied config is never read
 * mid-hit. Module switches, anticheat mode, OCM coordination and debug flags
 * live in {@code config.yml} (bound to {@code getConfig()}, persisted by {@code
 * saveConfig()}); the server-wide knockback profile lives in {@code
 * knockback.yml}, a satellite file written here as a standalone {@link
 * YamlConfiguration} because nothing else writes it at runtime.</p>
 *
 * <p>Every method reaches {@code reloadAll()} and so must run where that is
 * safe — the global region thread on Folia. Menu callers dispatch through
 * {@code Scheduling.runGlobal}; the public API documents the same requirement.</p>
 */
public final class ManagementService {

    private final MentalPlugin plugin;

    public ManagementService(@NotNull MentalPlugin plugin) {
        this.plugin = plugin;
    }

    /** Re-reads every configuration file and converges modules; returns warnings. */
    public @NotNull List<String> reload() {
        return plugin.reloadAll();
    }

    public void setModuleEnabled(@NotNull String moduleId, boolean enabled) {
        plugin.getConfig().set("modules." + moduleId, enabled);
        plugin.saveConfig();
        plugin.reloadAll();
    }

    public void setDebugEnabled(boolean enabled) {
        plugin.getConfig().set("debug.enabled", enabled);
        plugin.saveConfig();
        plugin.reloadAll();
    }

    public void setDebugCategory(@NotNull String categoryKey, boolean enabled) {
        plugin.getConfig().set("debug.categories." + categoryKey, enabled);
        plugin.saveConfig();
        plugin.reloadAll();
    }

    public void setAnticheatMode(@NotNull AnticheatMode mode) {
        plugin.getConfig().set("anticheat.mode", token(mode.name()));
        plugin.saveConfig();
        plugin.reloadAll();
    }

    public void setOcmCoordination(@NotNull OcmCoordination mode) {
        plugin.getConfig().set("compatibility.old-combat-mechanics", token(mode.name()));
        plugin.saveConfig();
        plugin.reloadAll();
    }

    /**
     * Sets the server-wide knockback profile (the {@code knockback.profile} key
     * in knockback.yml), reloads, and fires {@link KnockbackProfileChangeEvent}.
     * Returns false — changing nothing — when no profile by that name is loaded.
     */
    public boolean setGlobalProfile(@NotNull String profileName) {
        String name = profileName.trim().toLowerCase(Locale.ROOT);
        if (plugin.services().config().knockback().byName(name) == null) {
            return false;
        }
        String previous = plugin.services().config().knockback().defaultProfile();
        if (previous.equals(name)) {
            return true; // already the active profile — no write, no event.
        }
        File file = new File(plugin.getDataFolder(), ConfigStore.KNOCKBACK_FILE);
        YamlConfiguration yaml = loadYaml(file);
        yaml.set("knockback.profile", name);
        if (!saveYaml(yaml, file)) {
            return false;
        }
        plugin.reloadAll();
        new KnockbackProfileChangeEvent(previous, name).callEvent();
        return true;
    }

    /** Lower-cases an enum name and renders the {@code FORCE_SAFE} style as {@code force-safe}. */
    private static @NotNull String token(@NotNull String enumName) {
        return enumName.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private @NotNull YamlConfiguration loadYaml(@NotNull File file) {
        YamlConfiguration yaml = new YamlConfiguration();
        if (file.isFile()) {
            try (InputStreamReader reader = new InputStreamReader(
                    Files.newInputStream(file.toPath()), StandardCharsets.UTF_8)) {
                yaml.load(reader);
            } catch (IOException | InvalidConfigurationException failure) {
                plugin.getLogger().warning("Could not read " + ConfigStore.KNOCKBACK_FILE
                        + " for a management write: " + failure.getMessage());
            }
        }
        return yaml;
    }

    private boolean saveYaml(@NotNull YamlConfiguration yaml, @NotNull File file) {
        try {
            yaml.save(file);
            return true;
        } catch (IOException failure) {
            plugin.getLogger().warning("Could not write " + ConfigStore.KNOCKBACK_FILE
                    + ": " + failure.getMessage());
            return false;
        }
    }
}
