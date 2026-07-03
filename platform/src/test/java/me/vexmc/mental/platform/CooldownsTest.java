package me.vexmc.mental.platform;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the item-cooldown capability probe. The floor API (1.17.1) declares
 * {@code HumanEntity#setCooldown(Material,int)} (added 1.11.2), so the probe reports supported there and
 * the whole 1.17.1→26.x range keeps the real cooldown behaviour. The unsupported branch — a documented,
 * loud no-op because vanilla {@code <=} 1.10 has no pearl cooldown — is exercised live on 1.9.4/1.10.2.
 */
class CooldownsTest {

    @Test
    void modernApiReportsItemCooldownSupported() {
        assertTrue(Cooldowns.itemCooldownSupported());
    }
}
