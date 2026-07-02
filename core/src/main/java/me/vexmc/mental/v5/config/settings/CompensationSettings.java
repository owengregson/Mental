package me.vexmc.mental.v5.config.settings;

import me.vexmc.mental.v5.config.ProbeStrategy;

/**
 * The latency-compensation feature's tunables, ported field-for-field (minus
 * the module toggle) from the retired {@code config.CompensationSettings}. The
 * YAML surface is frozen; defaults are byte-identical.
 */
public record CompensationSettings(
        ProbeStrategy probeStrategy,
        int pingOffsetMillis,
        int spikeThresholdMillis,
        long probeIntervalTicks,
        long combatTimeoutTicks,
        boolean offGroundSync) {

    public static final CompensationSettings DEFAULTS =
            new CompensationSettings(ProbeStrategy.PING, 25, 20, 5L, 30L, true);
}
