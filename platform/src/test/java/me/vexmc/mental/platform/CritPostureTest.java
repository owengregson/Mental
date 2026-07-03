package me.vexmc.mental.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins the crit-posture accessor selection. The floor API (1.17.1) declares {@code isClimbing()} (1.17),
 * {@code isInWater()} (1.16) and {@code getAttackCooldown()} (1.15.2), so all three resolve to their modern
 * Bukkit methods there — byte-identical to the pre-backport direct calls across 1.17.1→26.x, no crit-feel
 * change on the modern range. The legacy fallbacks (feet-block climbable/water reads; NMS attack-strength /
 * fully-charged default) are exercised on the legacy servers where the Bukkit methods are absent.
 */
class CritPostureTest {

    @Test
    void modernApiSelectsBukkitMethodsForAllThreeReads() {
        assertEquals("climbing=BUKKIT_METHOD in-water=BUKKIT_METHOD attack-charge=BUKKIT_METHOD",
                CritPosture.describe());
    }
}
