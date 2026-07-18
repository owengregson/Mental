package me.vexmc.mental.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins the boot-time single-effect-accessor selection. The floor API (1.17.1) declares
 * {@code LivingEntity#getPotionEffect(PotionEffectType)} (added 1.10.2), so the resolver must pick the
 * direct Bukkit accessor there — byte-identical to the pre-backport direct call across 1.17.1→26.x. The
 * {@code getActivePotionEffects()} scan rung is exercised live on 1.9.4, the one revision where the direct
 * accessor is absent.
 */
class PotionEffectsTest {

    @Test
    void modernApiSelectsDirectAccessor() {
        assertEquals("LivingEntity#getPotionEffect(type)", PotionEffects.describe());
    }
}
