package me.vexmc.mental.v5.config;

import java.util.function.Consumer;

/**
 * How latency probes travel.
 *
 * <p>{@link #PING} uses the dedicated play ping/pong channel (Mental's original
 * 1.17 floor): purpose-built, no disconnect semantics, a private id space. It is
 * the transport Mental measures over from 1.17 up. {@link #TRANSACTION} uses
 * window-confirmation (a windowId-0 transaction the vanilla client echoes) — the
 * ecosystem-standard probe on 1.8–1.16, where the play PING/PONG channel does not
 * exist on the wire. {@link #KEEPALIVE} is <strong>retired</strong>: it is still a
 * selectable config value for backward compatibility, but {@link #resolveEffective}
 * loudly falls back to the version's real transport rather than silently ignoring
 * the selection.</p>
 *
 * <p>The effective transport is determined by the server version, never the raw
 * selection: below 1.17 there is only {@link #TRANSACTION}, at/above only
 * {@link #PING}. The configured value merely decides which loud reconciliation
 * line prints. This keeps the config version-blind (the parser stores the raw
 * value) while the wire mapping lives at the one boot seam that knows the
 * version.</p>
 */
public enum ProbeStrategy {
    PING,
    TRANSACTION,
    KEEPALIVE;

    /**
     * Resolves the configured strategy to the one Mental actually uses on this
     * server, emitting exactly one loud line when the selection differs from the
     * wire's real transport.
     *
     * <p>{@code legacy} is true below Paper 1.17 (no play PING/PONG channel):</p>
     * <ul>
     *   <li>below 1.17 the effective transport is {@link #TRANSACTION}. A configured
     *       {@link #PING} (the default) resolves to it via {@code info} — the expected
     *       legacy path, not a misconfiguration.</li>
     *   <li>at/above 1.17 the effective transport is {@link #PING}. A configured
     *       {@link #TRANSACTION} (a legacy-only transport) resolves to it via
     *       {@code warn}.</li>
     *   <li>{@link #KEEPALIVE} always retires via {@code warn} to whichever transport
     *       the version dictates.</li>
     * </ul>
     * When the selection already equals the effective transport, no line prints and
     * {@code parse(empty)} stays byte-identical on modern servers (default is PING).
     */
    public static ProbeStrategy resolveEffective(
            ProbeStrategy selected, boolean legacy, Consumer<String> info, Consumer<String> warn) {
        ProbeStrategy effective = legacy ? TRANSACTION : PING;
        if (selected == effective) {
            return effective;
        }
        switch (selected) {
            case KEEPALIVE -> warn.accept("KEEPALIVE probe strategy is retired; using " + effective);
            case PING ->
                    // Only reachable on legacy (on modern PING is already the effective
                    // transport): the play channel is absent below 1.17, so probes ride
                    // window-confirmation transactions instead. Informational, not an error.
                    info.accept("PING needs the 1.17 play channel, absent on this server; "
                            + "using window-confirmation TRANSACTION probes");
            case TRANSACTION ->
                    // Only reachable on modern (on legacy TRANSACTION is already effective):
                    // the admin asked for a pre-1.17 transport on 1.17+, a misconfiguration.
                    warn.accept("TRANSACTION is a pre-1.17 transport; on this server using PING");
        }
        return effective;
    }
}
