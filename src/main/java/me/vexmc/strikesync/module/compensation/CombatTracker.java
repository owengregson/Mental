package me.vexmc.strikesync.module.compensation;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which players are currently "in combat" so we only spend ping probes
 * on the people who actually need accurate latency measurement.
 *
 * <p>
 * State is just a set of {@link UUID} → {@code lastHit} timestamps.
 * Stale entries are reaped lazily by the periodic probe task.
 */
final class CombatTracker {

	private final ConcurrentHashMap<UUID, Long> tagged = new ConcurrentHashMap<>();

	void mark(UUID player, long now) {
		tagged.put(player, now);
	}

	void evictExpired(long now, long timeoutMillis) {
		tagged.entrySet().removeIf(e -> now - e.getValue() > timeoutMillis);
	}

	Set<UUID> snapshot() {
		return Set.copyOf(tagged.keySet());
	}

	boolean isTagged(UUID player) {
		return tagged.containsKey(player);
	}

	void forget(UUID player) {
		tagged.remove(player);
	}

	void clear() {
		tagged.clear();
	}
}
