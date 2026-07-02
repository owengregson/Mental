package me.vexmc.mental.v5.config;

import java.util.function.Consumer;

/**
 * How latency probes travel (frozen from the retired {@code config.ProbeStrategy}).
 *
 * <p>{@link #PING} uses the dedicated play ping/pong channel (Mental's 1.17
 * floor): purpose-built, no disconnect semantics, a private id space. It is the
 * only transport Mental measures over. {@link #KEEPALIVE} is <strong>retired</strong>
 * — it is still a selectable config value for backward compatibility, but
 * {@link #resolveEffective} loudly warns and falls back to {@link #PING} rather
 * than silently ignoring the selection.</p>
 */
public enum ProbeStrategy {
    PING,
    KEEPALIVE;

    /**
     * Resolves the configured strategy to the one Mental actually uses.
     * {@link #KEEPALIVE} is retired (Mental measures only over the dedicated Play
     * PING/PONG channel), so selecting it emits ONE loud warning through
     * {@code warn} and falls back to {@link #PING}. Any other selection passes
     * through unchanged.
     */
    public static ProbeStrategy resolveEffective(ProbeStrategy selected, Consumer<String> warn) {
        if (selected == KEEPALIVE) {
            warn.accept("KEEPALIVE probe strategy is retired; using PING");
            return PING;
        }
        return selected;
    }
}
