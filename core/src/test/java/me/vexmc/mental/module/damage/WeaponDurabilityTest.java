package me.vexmc.mental.module.damage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pure test for the effective-max choice (the Bukkit shell that reflects the {@code max_damage} component
 * is verified live). A display-swapped diamond-in-disguise carrying a diamond {@code max_damage} must wear
 * against that, not the gold material's 32.
 */
class WeaponDurabilityTest {

    @Test
    void aCustomComponentMaxWinsOverTheMaterialMax() {
        // Gold sword material max 32, diamond max_damage component 1561 → wears like diamond.
        assertEquals(1561, WeaponDurability.effectiveMax(32, true, 1561));
    }

    @Test
    void noCustomMaxUsesTheMaterialMax() {
        assertEquals(32, WeaponDurability.effectiveMax(32, false, 1561));
        assertEquals(1561, WeaponDurability.effectiveMax(1561, false, 0));
    }

    @Test
    void aNonPositiveCustomMaxIsIgnored() {
        // Defensive: a zero/absent custom value must never shorten the material max.
        assertEquals(32, WeaponDurability.effectiveMax(32, true, 0));
    }
}
