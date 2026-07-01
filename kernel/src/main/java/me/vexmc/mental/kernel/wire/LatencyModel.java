package me.vexmc.mental.kernel.wire;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player latency state: outstanding probe ids, the last two RTT samples
 * (spike detection), and rolling jitter.
 *
 * <p>{@link Record#onResponse} is exact-match and only mutates on a hit, so
 * the probe listener can blindly offer every inbound response id: vanilla and
 * third-party transactions match nothing and flow through untouched, while
 * Mental's probes are consumed exactly once.</p>
 */
public final class LatencyModel {

    /** Outbound keep-alive probe id base — distinctive for tracing, never required to be unique. */
    public static final long KEEPALIVE_ID_BASE = 0x5_F1C_5_5C0_DEL;

    /** Outbound play-ping probe id base ('ME' in the high half of a positive int). */
    public static final int PING_ID_BASE = 0x4D45_0000;

    private static final int MAX_OUTSTANDING = 32;

    private final ConcurrentHashMap<UUID, Record> records = new ConcurrentHashMap<>();

    public Record forPlayer(UUID id) {
        return records.computeIfAbsent(id, key -> new Record());
    }

    public void forget(UUID id) {
        records.remove(id);
    }

    public void clear() {
        records.clear();
    }

    public static final class Record {

        private final ConcurrentHashMap<Long, Long> outstanding = new ConcurrentHashMap<>();
        private final JitterCalculator jitter = new JitterCalculator();
        private volatile Double pingMillis;
        private volatile Double previousPingMillis;

        public void onProbeSent(long probeId, long nowNanos) {
            outstanding.put(probeId, nowNanos);
            if (outstanding.size() > MAX_OUTSTANDING) {
                evictOldest();
            }
        }

        /** True when the id matched one of ours (caller cancels the packet). */
        public boolean onResponse(long probeId, long nowNanos) {
            Long sentNanos = outstanding.remove(probeId);
            if (sentNanos == null) {
                return false;
            }
            long rttNanos = nowNanos - sentNanos;
            previousPingMillis = pingMillis;
            pingMillis = rttNanos / 1_000_000.0;
            jitter.addPing(rttNanos);
            return true;
        }

        public Double pingMillis() {
            return pingMillis;
        }

        public Double previousPingMillis() {
            return previousPingMillis;
        }

        public double jitterMillis() {
            return jitter.calculateMillis();
        }

        private void evictOldest() {
            long oldestId = -1L;
            long oldestSent = Long.MAX_VALUE;
            for (Map.Entry<Long, Long> entry : outstanding.entrySet()) {
                if (entry.getValue() < oldestSent) {
                    oldestSent = entry.getValue();
                    oldestId = entry.getKey();
                }
            }
            if (oldestId != -1L) {
                outstanding.remove(oldestId);
            }
        }
    }
}
