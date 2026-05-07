package me.vexmc.strikesync.config;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Immutable view over the {@code compensation} config section.
 *
 * <p>
 * Latency compensation rewrites the vertical component of incoming knockback
 * to match what a player would have experienced clientside, eliminating the
 * "high-ping = less knockback" disadvantage. See
 * {@code me.vexmc.strikesync.module.compensation.LatencyCompensationModule} for
 * the full rationale.
 *
 * @param enabled              master switch.
 * @param pingOffsetMillis     hard offset subtracted from measured ping; 25ms
 *                             is the
 *                             widely-accepted "sweet spot" from competitive
 *                             PvP.
 * @param spikeThresholdMillis if (ping − previousPing) exceeds this, use the
 *                             previous
 *                             reading to ignore one-shot lag spikes.
 * @param probeIntervalTicks   how often to send a ping probe to combat-tagged
 *                             players.
 * @param combatTimeoutTicks   how long after the last hit a player is
 *                             considered "in combat".
 * @param offGroundSync        whether to also rewrite Y when the victim is
 *                             airborne.
 */
public record CompensationSettings(
		boolean enabled,
		long pingOffsetMillis,
		long spikeThresholdMillis,
		long probeIntervalTicks,
		long combatTimeoutTicks,
		boolean offGroundSync) {

	public static CompensationSettings from(ConfigurationSection s) {
		return new CompensationSettings(
				s.getBoolean("enabled", true),
				Math.max(0, s.getLong("ping-offset-ms", 25L)),
				Math.max(0, s.getLong("spike-threshold-ms", 20L)),
				Math.max(1, s.getLong("probe-interval-ticks", 5L)),
				Math.max(1, s.getLong("combat-timeout-ticks", 30L)),
				s.getBoolean("off-ground-sync", true));
	}
}
