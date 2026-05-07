package me.vexmc.strikesync.module.hitreg;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player clicks-per-second rate limiter using a sliding 1-second window of
 * timestamps. Thread-safe: every operation is local to the per-UUID record
 * and synchronised on it briefly.
 *
 * <p>
 * The cap is treated as "if a hit would be the (cap+1)th in the last second,
 * drop it". A cap of {@code 0} disables limiting entirely.
 */
final class CpsLimiter {

	private static final long WINDOW_MILLIS = 1_000L;

	private final ConcurrentHashMap<UUID, Bucket> buckets = new ConcurrentHashMap<>();

	/**
	 * Attempt to register a hit at {@code now}. Returns {@code true} if the hit
	 * fits within {@code maxCps} during the trailing 1-second window.
	 */
	boolean tryAcquire(UUID player, int maxCps, long now) {
		if (maxCps <= 0)
			return true;
		Bucket bucket = buckets.computeIfAbsent(player, k -> new Bucket(maxCps));
		return bucket.tryAcquire(maxCps, now);
	}

	/** Forget a player; called on quit to keep the map small. */
	void forget(UUID player) {
		buckets.remove(player);
	}

	/** Drop everything (used on disable). */
	void clear() {
		buckets.clear();
	}

	private static final class Bucket {
		// Ring buffer of timestamps (millis). Capacity is sized once to maxCps.
		private long[] window;
		private int head;
		private int size;

		Bucket(int capacity) {
			this.window = new long[Math.max(1, capacity)];
		}

		synchronized boolean tryAcquire(int capacity, long now) {
			if (window.length != Math.max(1, capacity)) {
				window = new long[Math.max(1, capacity)];
				head = 0;
				size = 0;
			}
			// If the window is full, the oldest timestamp must be older than 1s.
			if (size == window.length) {
				long oldest = window[head];
				if (now - oldest < WINDOW_MILLIS) {
					return false;
				}
				window[head] = now;
				head = (head + 1) % window.length;
				return true;
			}
			int tail = (head + size) % window.length;
			window[tail] = now;
			size++;
			return true;
		}
	}
}
