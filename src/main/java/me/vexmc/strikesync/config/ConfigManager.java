package me.vexmc.strikesync.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the typed configuration snapshot.
 *
 * <p>
 * {@link #reload()} reads {@code config.yml}, builds fresh
 * {@link HitRegSettings}
 * / {@link KnockbackSettings} / {@link DebugSettings} records, and atomically
 * swaps
 * them in. Modules read their settings via the typed getters, so a partial
 * reload
 * never produces a torn snapshot.
 */
public final class ConfigManager {

	private final JavaPlugin plugin;

	private final AtomicReference<HitRegSettings> hitReg = new AtomicReference<>();
	private final AtomicReference<KnockbackSettings> knockback = new AtomicReference<>();
	private final AtomicReference<CompensationSettings> compensation = new AtomicReference<>();
	private final AtomicReference<DebugSettings> debug = new AtomicReference<>();

	public ConfigManager(JavaPlugin plugin) {
		this.plugin = plugin;
	}

	/** Reload {@code config.yml} from disk and rebuild every typed snapshot. */
	public void reload() {
		plugin.saveDefaultConfig();
		plugin.reloadConfig();
		FileConfiguration cfg = plugin.getConfig();

		hitReg.set(HitRegSettings.from(orEmpty(cfg.getConfigurationSection("async-hitreg"), cfg, "async-hitreg")));
		knockback.set(KnockbackSettings.from(orEmpty(cfg.getConfigurationSection("knockback"), cfg, "knockback")));
		compensation.set(
				CompensationSettings.from(orEmpty(cfg.getConfigurationSection("compensation"), cfg, "compensation")));
		debug.set(DebugSettings.from(orEmpty(cfg.getConfigurationSection("debug"), cfg, "debug")));
	}

	public HitRegSettings hitReg() {
		return hitReg.get();
	}

	public KnockbackSettings knockback() {
		return knockback.get();
	}

	public CompensationSettings compensation() {
		return compensation.get();
	}

	public DebugSettings debug() {
		return debug.get();
	}

	/** Persist a top-level config change (used by command toggles). */
	public void set(String path, Object value) {
		plugin.getConfig().set(path, value);
		plugin.saveConfig();
	}

	private static ConfigurationSection orEmpty(ConfigurationSection section,
			FileConfiguration root,
			String key) {
		return section != null ? section : root.createSection(key);
	}
}
