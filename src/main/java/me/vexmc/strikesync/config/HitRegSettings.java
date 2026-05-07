package me.vexmc.strikesync.config;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Immutable view over the {@code async-hitreg} config section.
 *
 * @param enabled whether StrikeSync should intercept attack packets at all.
 * @param maxCps  maximum sustained clicks-per-second per player; {@code 0}
 *                disables the cap.
 */
public record HitRegSettings(boolean enabled, int maxCps) {

	public static HitRegSettings from(ConfigurationSection section) {
		boolean enabled = section.getBoolean("enabled", true);
		int maxCps = Math.max(0, section.getInt("max-cps", 20));
		return new HitRegSettings(enabled, maxCps);
	}

	public boolean rateLimited() {
		return maxCps > 0;
	}
}
