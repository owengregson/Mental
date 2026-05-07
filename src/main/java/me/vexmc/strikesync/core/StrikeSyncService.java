package me.vexmc.strikesync.core;

import me.vexmc.strikesync.config.ConfigManager;
import me.vexmc.strikesync.util.Logging;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;

/**
 * Container for everything modules need to do their job: the plugin instance,
 * the typed config manager, and the shared logger.
 *
 * <p>
 * PacketEvents is intentionally <em>not</em> held here — its API is
 * a global singleton accessed via {@code PacketEvents.getAPI()} once the
 * plugin's {@code onLoad} hook has set it up. Treating it as ambient state
 * means modules don't need a reference threaded through their constructors
 * just to register a listener.
 */
public final class StrikeSyncService {

	private final JavaPlugin plugin;
	private final ConfigManager config;
	private final Logging log;

	/**
	 * Open registry of singleton "things modules want to expose to commands"
	 * keyed by class. Lighter than reflection or full DI; type-safe at the
	 * call site via {@link #lookup}.
	 */
	private final Map<Class<?>, Object> exports = new HashMap<>();

	public StrikeSyncService(JavaPlugin plugin,
			ConfigManager config,
			Logging log) {
		this.plugin = plugin;
		this.config = config;
		this.log = log;
	}

	public JavaPlugin plugin() {
		return plugin;
	}

	public ConfigManager config() {
		return config;
	}

	public Logging log() {
		return log;
	}

	public <T> void publish(Class<T> type, T instance) {
		exports.put(type, instance);
	}

	public <T> T lookup(Class<T> type) {
		Object value = exports.get(type);
		return type.cast(value);
	}
}
