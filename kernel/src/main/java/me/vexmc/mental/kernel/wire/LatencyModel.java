package me.vexmc.mental.kernel.wire;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player latency state: outstanding probe ids, the last two RTT samples
 * (consumed by {@link Record#filteredPingMillis}), and rolling jitter.
 *
 * <p>{@link Record#onResponse} is exact-match and only mutates on a hit, so
 * the probe listener can blindly offer every inbound response id: vanilla and
 * third-party transactions match nothing and flow through untouched, while
 * Mental's probes are consumed exactly once.</p>
 */
public final class LatencyModel {

    /** Outbound play-ping probe id base ('ME' in the high half of a positive int). */
    public static final int PING_ID_BASE = 0x4D45_0000;

    /**
     * Outbound window-confirmation probe id base ('MT' in the high half of a
     * positive int) — the pre-1.17 transport's id space. It differs from
     * {@link #PING_ID_BASE} in the high 16 bits, so the two probe id spaces are
     * disjoint as unsigned longs no matter what the low 16 bits carry. The model
     * itself stays mechanism-blind: a base is just a private slice of the opaque
     * id range so vanilla/third-party echoes cannot alias a probe.
     */
    public static final int TRANSACTION_ID_BASE = 0x4D54_0000;

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

        /**
         * The spike-filtered RTT reading (the spike-threshold-ms contract): when the two
         * most recent samples disagree by more than the threshold, the SMALLER is trusted —
         * a round trip can be overstated by a delayed echo but never understated, so a
         * one-off upward spike is rejected on arrival AND on bounce-back while a sustained
         * shift is adopted on its second sample. {@code threshold <= 0} disables the
         * filter. Null until the first response.
         */
        public Double filteredPingMillis(int spikeThresholdMillis) {
            Double ping = this.pingMillis;
            Double previous = this.previousPingMillis;
            if (ping == null) {
                return null;
            }
            if (spikeThresholdMillis > 0 && previous != null
                    && Math.abs(ping - previous) > spikeThresholdMillis) {
                return Math.min(ping, previous);
            }
            return ping;
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
