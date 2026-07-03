package me.vexmc.mental.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins the boot-time absorption-accessor selection. The tests compile against the floor API (1.17.1),
 * which declares {@code LivingEntity#getAbsorptionAmount()} (added on {@code Damageable} at 1.15), so the
 * resolver must pick the modern Bukkit accessor there — the accessor the whole 1.17.1→26.x range uses,
 * guaranteeing zero behavioural change on modern servers. The NMS {@code getAbsorptionHearts()} rung is
 * exercised live on the legacy servers (1.9.4–1.13.2), where the Bukkit method is absent; a unit test
 * cannot present a server that lacks it.
 */
class AbsorptionsTest {

    @Test
    void modernApiSelectsBukkitAbsorption() {
        assertEquals("LivingEntity#getAbsorptionAmount()", Absorptions.describe());
    }
}
