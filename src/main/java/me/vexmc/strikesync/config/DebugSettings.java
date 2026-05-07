package me.vexmc.strikesync.config;

import org.bukkit.configuration.ConfigurationSection;

/** Immutable view over the {@code debug} config section. */
public record DebugSettings(boolean enabled) {

	public static DebugSettings from(ConfigurationSection s) {
		return new DebugSettings(s.getBoolean("enabled", false));
	}
}
