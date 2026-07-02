package me.vexmc.mental.v5.config;

/**
 * How latency probes travel (frozen from the retired {@code config.ProbeStrategy}).
 *
 * <p>{@link #PING} uses the dedicated play ping/pong channel (Mental's 1.17
 * floor): purpose-built, no disconnect semantics, a private id space.
 * {@link #KEEPALIVE} measures over keep-alive packets, Mental answering its
 * own probes before vanilla sees them.</p>
 */
public enum ProbeStrategy {
    PING,
    KEEPALIVE
}
