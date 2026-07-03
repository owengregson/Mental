package me.vexmc.mental.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the boot-time ping-accessor selection. The tests compile against the floor API (1.17.1), which
 * declares {@code Player#getPing()}, so the resolver must pick the modern accessor there — the same
 * accessor the whole 1.16.5→26.x range uses, guaranteeing zero behavioural change on modern servers. The
 * Spigot and NMS rungs are exercised live on legacy servers by the boot suite (unit tests cannot present a
 * server that lacks {@code Player#getPing()}).
 */
class PingsTest {

    @Test
    void modernApiSelectsPlayerGetPing() {
        assertEquals("Player#getPing()", Pings.describe());
    }

    @Test
    void persistentDataContainerIsPresentOnTheFloorApi() {
        // A companion pin: the floor API (1.17.1) has PDC, so the modern path (not the in-memory fallback)
        // is what the modern gate exercises — the legacy fallback is proven live on the legacy servers.
        assertTrue(PersistentData.supported());
    }
}
