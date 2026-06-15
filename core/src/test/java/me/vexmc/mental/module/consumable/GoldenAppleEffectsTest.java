package me.vexmc.mental.module.consumable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import me.vexmc.mental.module.consumable.GoldenAppleEffects.EffectSpec;
import org.junit.jupiter.api.Test;

/**
 * Pins the decompile-cited 1.8 era effect tables for both golden apple types.
 *
 * <p>Sources: {@code zt.java:22,26,27,28} (1.8.9 Items) and
 * {@code zw.java:641} (1.8.9 EntityHuman food-eat path), cited in
 * {@code docs/superpowers/plans/2026-06-14-ocm-ground-truth.md §6}.</p>
 *
 * <p>Using plain String keys (not {@code PotionEffectType}) keeps this
 * class free of the Bukkit server registry — it runs in a plain JUnit
 * environment without MockBukkit.</p>
 */
class GoldenAppleEffectsTest {

    /* ------------------------------------------------------------------ */
    /*  Normal golden apple                                                 */
    /* ------------------------------------------------------------------ */

    @Test
    void normalApple_hasTwoEffects() {
        assertEquals(2, GoldenAppleEffects.normalApple().size(),
                "normal golden apple must give exactly 2 effects");
    }

    @Test
    void normalApple_regenerationII_100t() {
        // Regeneration II = amplifier 1 (0-based); 100 ticks.
        // [zt.java:22 / zw.java:641]
        EffectSpec spec = findByKey(GoldenAppleEffects.normalApple(), "regeneration");
        assertNotNull(spec, "normal apple must include regeneration");
        assertEquals(1, spec.amplifier(),
                "normal apple: Regeneration II → amplifier 1 (0-based)");
        assertEquals(100, spec.durationTicks(),
                "normal apple: Regeneration II duration must be 100 ticks");
    }

    @Test
    void normalApple_absorptionI_2400t() {
        // Absorption I = amplifier 0; 2400 ticks (2 minutes).
        // [zt.java:22 / zw.java:641]
        EffectSpec spec = findByKey(GoldenAppleEffects.normalApple(), "absorption");
        assertNotNull(spec, "normal apple must include absorption");
        assertEquals(0, spec.amplifier(),
                "normal apple: Absorption I → amplifier 0 (0-based)");
        assertEquals(2400, spec.durationTicks(),
                "normal apple: Absorption I duration must be 2400 ticks");
    }

    /* ------------------------------------------------------------------ */
    /*  Enchanted (notch) golden apple                                     */
    /* ------------------------------------------------------------------ */

    @Test
    void notchApple_hasFourEffects() {
        assertEquals(4, GoldenAppleEffects.notchApple().size(),
                "enchanted golden apple must give exactly 4 effects");
    }

    @Test
    void notchApple_regenerationV_600t() {
        // Regeneration V = amplifier 4 (0-based); 600 ticks (30 seconds).
        // [zt.java:22]
        EffectSpec spec = findByKey(GoldenAppleEffects.notchApple(), "regeneration");
        assertNotNull(spec, "notch apple must include regeneration");
        assertEquals(4, spec.amplifier(),
                "notch apple: Regeneration V → amplifier 4 (0-based)");
        assertEquals(600, spec.durationTicks(),
                "notch apple: Regeneration V duration must be 600 ticks");
    }

    @Test
    void notchApple_resistanceI_6000t() {
        // Resistance I = amplifier 0; 6000 ticks (5 minutes).
        // [zt.java:26]
        EffectSpec spec = findByKey(GoldenAppleEffects.notchApple(), "resistance");
        assertNotNull(spec, "notch apple must include resistance");
        assertEquals(0, spec.amplifier(),
                "notch apple: Resistance I → amplifier 0 (0-based)");
        assertEquals(6000, spec.durationTicks(),
                "notch apple: Resistance I duration must be 6000 ticks");
    }

    @Test
    void notchApple_fireResistanceI_6000t() {
        // Fire Resistance I = amplifier 0; 6000 ticks.
        // [zt.java:27]
        EffectSpec spec = findByKey(GoldenAppleEffects.notchApple(), "fire_resistance");
        assertNotNull(spec, "notch apple must include fire_resistance");
        assertEquals(0, spec.amplifier(),
                "notch apple: Fire Resistance I → amplifier 0 (0-based)");
        assertEquals(6000, spec.durationTicks(),
                "notch apple: Fire Resistance I duration must be 6000 ticks");
    }

    @Test
    void notchApple_absorptionI_2400t() {
        // Absorption I (NOT IV) = amplifier 0; 2400 ticks.
        // Era truth: the enchanted apple gives Absorption I, same as the normal
        // apple. OCM and various wikis list IV; the 1.8.9 decompile says I.
        // [zt.java:28]
        EffectSpec spec = findByKey(GoldenAppleEffects.notchApple(), "absorption");
        assertNotNull(spec, "notch apple must include absorption");
        assertEquals(0, spec.amplifier(),
                "notch apple: Absorption is I (amp 0), NOT IV — verify against zt.java:28");
        assertEquals(2400, spec.durationTicks(),
                "notch apple: Absorption duration must be 2400 ticks");
    }

    /* ------------------------------------------------------------------ */
    /*  Helper                                                              */
    /* ------------------------------------------------------------------ */

    private static EffectSpec findByKey(List<EffectSpec> list, String key) {
        return list.stream()
                .filter(s -> s.effectKey().equals(key))
                .findFirst()
                .orElse(null);
    }
}
