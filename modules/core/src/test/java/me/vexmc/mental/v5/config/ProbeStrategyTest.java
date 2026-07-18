package me.vexmc.mental.v5.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the version-aware transport reconciliation. The effective transport is
 * determined by the server version, never the raw selection; the configured value
 * only decides which loud line prints (info for the expected legacy default,
 * warn for a misconfiguration or the retired KEEPALIVE).
 */
class ProbeStrategyTest {

    private final List<String> info = new ArrayList<>();
    private final List<String> warn = new ArrayList<>();

    private ProbeStrategy resolve(ProbeStrategy selected, boolean legacy) {
        return ProbeStrategy.resolveEffective(selected, legacy, info::add, warn::add);
    }

    @Test
    void pingOnModernStaysPingSilently() {
        assertEquals(ProbeStrategy.PING, resolve(ProbeStrategy.PING, false));
        assertTrue(info.isEmpty(), "the default modern path must be silent (byte-identical)");
        assertTrue(warn.isEmpty());
    }

    @Test
    void pingOnLegacyResolvesToTransactionWithAnInfoLine() {
        assertEquals(ProbeStrategy.TRANSACTION, resolve(ProbeStrategy.PING, true));
        assertEquals(1, info.size(), () -> "expected one info line, got " + info);
        assertTrue(info.get(0).contains("TRANSACTION"), () -> "info line missing the transport: " + info.get(0));
        assertTrue(warn.isEmpty(), "the legacy default path is not a warning");
    }

    @Test
    void transactionOnLegacyStaysTransactionSilently() {
        assertEquals(ProbeStrategy.TRANSACTION, resolve(ProbeStrategy.TRANSACTION, true));
        assertTrue(info.isEmpty());
        assertTrue(warn.isEmpty(), "an explicit legacy transport selection is not a warning");
    }

    @Test
    void transactionOnModernResolvesToPingWithAWarn() {
        assertEquals(ProbeStrategy.PING, resolve(ProbeStrategy.TRANSACTION, false));
        assertTrue(info.isEmpty());
        assertEquals(1, warn.size(), () -> "expected one warn line, got " + warn);
        assertTrue(warn.get(0).contains("pre-1.17"), () -> "warn line missing the misconfig hint: " + warn.get(0));
    }

    @Test
    void keepaliveRetiresLoudlyToTheVersionsTransport() {
        assertEquals(ProbeStrategy.PING, resolve(ProbeStrategy.KEEPALIVE, false));
        assertEquals(1, warn.size());
        assertTrue(warn.get(0).contains("KEEPALIVE") && warn.get(0).contains("PING"),
                () -> "modern KEEPALIVE retirement missing the transport: " + warn.get(0));

        warn.clear();
        assertEquals(ProbeStrategy.TRANSACTION, resolve(ProbeStrategy.KEEPALIVE, true));
        assertEquals(1, warn.size());
        assertTrue(warn.get(0).contains("KEEPALIVE") && warn.get(0).contains("TRANSACTION"),
                () -> "legacy KEEPALIVE retirement missing the transport: " + warn.get(0));
    }
}
