package me.vexmc.mental.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the {@code naturalRegeneration}-gamerule read probe.
 *
 * <p>The probe DECISION is the load-bearing part: {@code org.bukkit.GameRule}
 * lands at 1.13, so the seam must select the typed read where the class is present
 * and the deprecated {@code String} overload where it is absent. The floor API
 * (1.17.1) on the test classpath has {@code GameRule}, so the live strategy is
 * TYPED here and the below-floor fallback is exercised on 1.9.4–1.12.2 in the
 * integration matrix — but the fallback DECISION and the legacy value parse are
 * pinned directly below, without needing the class to actually be absent.</p>
 */
class NaturalRegenTest {

    @Test
    void classPresentSelectsTypedClassAbsentSelectsLegacy() {
        // The probe decision, both branches — the pinned fallback (absent ⇒ legacy).
        assertEquals(NaturalRegen.Strategy.TYPED, NaturalRegen.strategyFor(true),
                "GameRule present (1.13+) ⇒ the typed read");
        assertEquals(NaturalRegen.Strategy.LEGACY, NaturalRegen.strategyFor(false),
                "GameRule absent (pre-1.13) ⇒ the String-overload fallback");
    }

    @Test
    void liveStrategyIsTypedOnTheFloorApiClasspath() {
        // The floor API declares org.bukkit.GameRule, so the boot probe resolves TYPED
        // and the boot-report description names the typed constant.
        assertEquals(NaturalRegen.Strategy.TYPED, NaturalRegen.strategy());
        assertTrue(NaturalRegen.describe().contains("NATURAL_REGENERATION"),
                "the typed path is reported, was " + NaturalRegen.describe());
    }

    @Test
    void legacyStringValueMatchesTheTypedNullIsOffSemantics() {
        // Only the literal "true" enables regen; "false"/null/empty read off — the
        // same result the typed Boolean.TRUE.equals(...) path yields on a null rule,
        // so the two paths are byte-identical across the 1.13 boundary.
        assertTrue(NaturalRegen.interpretLegacy("true"), "\"true\" ⇒ on");
        assertFalse(NaturalRegen.interpretLegacy("false"), "\"false\" ⇒ off");
        assertFalse(NaturalRegen.interpretLegacy(null), "null ⇒ off (matches the typed null-is-off default)");
        assertFalse(NaturalRegen.interpretLegacy(""), "empty ⇒ off");
    }
}
