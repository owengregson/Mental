package me.vexmc.mental.config;

/**
 * How latency probes travel.
 *
 * <p>{@link #PING} uses the dedicated play ping/pong channel (added in 1.17,
 * exactly Mental's floor): purpose-built, no disconnect semantics, and a
 * private ID space that cannot collide with vanilla or anticheat
 * transactions. {@link #KEEPALIVE} measures over keep-alive packets — the
 * historically proven strategy — with Mental answering its own probes before
 * vanilla can see them.</p>
 */
public enum ProbeStrategy {
    PING,
    KEEPALIVE
}
