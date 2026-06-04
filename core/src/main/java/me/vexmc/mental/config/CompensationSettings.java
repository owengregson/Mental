package me.vexmc.mental.config;

import org.jetbrains.annotations.NotNull;

public record CompensationSettings(
        boolean enabled,
        @NotNull ProbeStrategy probeStrategy,
        int pingOffsetMillis,
        int spikeThresholdMillis,
        long probeIntervalTicks,
        long combatTimeoutTicks,
        boolean offGroundSync) {

    static final CompensationSettings DEFAULTS =
            new CompensationSettings(true, ProbeStrategy.PING, 25, 20, 5L, 30L, true);

    static @NotNull CompensationSettings parse(@NotNull ConfigReader reader) {
        return new CompensationSettings(
                reader.flag("enabled", DEFAULTS.enabled),
                reader.oneOf("probe-strategy", DEFAULTS.probeStrategy, ProbeStrategy.class),
                reader.intAtLeast("ping-offset-ms", DEFAULTS.pingOffsetMillis, 0),
                reader.intAtLeast("spike-threshold-ms", DEFAULTS.spikeThresholdMillis, 1),
                reader.ticksAtLeast("probe-interval-ticks", DEFAULTS.probeIntervalTicks, 1),
                reader.ticksAtLeast("combat-timeout-ticks", DEFAULTS.combatTimeoutTicks, 1),
                reader.flag("off-ground-sync", DEFAULTS.offGroundSync));
    }
}
