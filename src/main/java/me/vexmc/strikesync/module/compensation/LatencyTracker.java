package me.vexmc.strikesync.module.compensation;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player latency state: outstanding ping-probe ids, the most recent two
 * RTT samples (for spike detection), and a rolling jitter calculator.
 *
 * <p>
 * The outstanding map is keyed by probe id, so {@link Record#onResponse}
 * is O(1) and — crucially — does <em>not</em> mutate state when called with
 * an id that isn't ours. This means the {@link PingProbe} listener can blindly
 * forward every incoming KEEP_ALIVE id; vanilla responses match nothing and
 * are silently ignored, while our probes are matched and consumed exactly once.
 */
final class LatencyTracker {

	/**
	 * Arbitrary starting offset for our outbound probe ids. Exact-match in
	 * {@link Record#onResponse} means we don't actually need a "no overlap with
	 * vanilla" guarantee — using a non-zero base just makes tracing easier.
	 */
	static final long PROBE_ID_BASE = 0x5_F1C_5_5C0_DEL;

	/** Bound the outstanding map to avoid leaks when a player never responds. */
	private static final int MAX_OUTSTANDING = 32;

	private final ConcurrentHashMap<UUID, Record> records = new ConcurrentHashMap<>();

	Record forPlayer(UUID id) {
		return records.computeIfAbsent(id, k -> new Record());
	}

	void forget(UUID id) {
		records.remove(id);
	}

	void clear() {
		records.clear();
	}

	static final class Record {

		/** id → sentNanos. */
		private final ConcurrentHashMap<Long, Long> outstanding = new ConcurrentHashMap<>();
		private final JitterCalculator jitter = new JitterCalculator();
		private volatile Double pingMs;
		private volatile Double previousPingMs;

		void onProbeSent(long probeId, long nowNanos) {
			outstanding.put(probeId, nowNanos);
			if (outstanding.size() > MAX_OUTSTANDING) {
				evictOldest();
			}
		}

		/**
		 * Try to match an inbound KEEP_ALIVE id against our outstanding probes.
		 * Returns {@code true} when it matched (so the caller can cancel the
		 * packet to keep vanilla's mismatched-id disconnect path quiet).
		 */
		boolean onResponse(long probeId, long nowNanos) {
			Long sentNanos = outstanding.remove(probeId);
			if (sentNanos == null)
				return false;

			long rttNanos = nowNanos - sentNanos;
			double rttMs = rttNanos / 1_000_000.0D;
			previousPingMs = pingMs;
			pingMs = rttMs;
			jitter.addPing(rttNanos);
			return true;
		}

		Double pingMs() {
			return pingMs;
		}

		Double previousPingMs() {
			return previousPingMs;
		}

		double jitterMs() {
			return jitter.calculateMillis();
		}

		/** Remove the entry with the smallest sentNanos. */
		private void evictOldest() {
			long oldestId = -1L;
			long oldestSent = Long.MAX_VALUE;
			for (Map.Entry<Long, Long> e : outstanding.entrySet()) {
				if (e.getValue() < oldestSent) {
					oldestSent = e.getValue();
					oldestId = e.getKey();
				}
			}
			if (oldestId != -1L)
				outstanding.remove(oldestId);
		}
	}
}
