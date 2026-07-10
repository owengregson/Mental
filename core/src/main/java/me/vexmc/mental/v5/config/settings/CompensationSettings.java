package me.vexmc.mental.v5.config.settings;

import me.vexmc.mental.v5.config.ProbeStrategy;

/**
 * The latency-compensation feature's tunables. {@code ping-offset-ms} was
 * retired in 2.4.9 — it was parsed but never applied (subtracting it could
 * never have an era-exact no-op default), so it is gone from the record; the
 * parser emits a one-line notice when a stale key lingers on disk. The
 * remaining knobs are consumed: {@code spikeThresholdMillis} by
 * {@code LatencyModel.Record.filteredPingMillis}, {@code offGroundSync} by
 * {@code CompensationQuery.verticalFor}.
 */
public record CompensationSettings(
        ProbeStrategy probeStrategy,
        int spikeThresholdMillis,
        long probeIntervalTicks,
        long combatTimeoutTicks,
        boolean offGroundSync) {

    public static final CompensationSettings DEFAULTS =
            new CompensationSettings(ProbeStrategy.PING, 20, 5L, 30L, true);
}
