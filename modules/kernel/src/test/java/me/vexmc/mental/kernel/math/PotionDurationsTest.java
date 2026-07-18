package me.vexmc.mental.kernel.math;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins the 1.8 potion-duration table that {@link PotionDurations} restores.
 *
 * <p>Every expectation here is OCM's {@code old-potion-effects.potion-durations}
 * config table (seconds) converted to ticks (×20). OCM's table is the de-facto
 * reference for the pre-1.9 durations; see
 * {@code docs/superpowers/plans/2026-06-14-ocm-ground-truth.md §5} which flags
 * the table as the agreed reference (not independently decompile-verified). The
 * source rows live in
 * {@code BukkitOldCombatMechanics/src/main/resources/config.yml} under
 * {@code old-potion-effects.potion-durations}.</p>
 *
 * <p>This class is pure (no Bukkit server registry), so it runs in a plain
 * JUnit environment without MockBukkit. Keys are the lowercase base potion-type
 * tokens OCM uses (e.g. {@code "speed"}/{@code "swiftness"},
 * {@code "jump"}/{@code "leaping"}).</p>
 */
class PotionDurationsTest {

    private static final int NONE = PotionDurations.NO_OVERRIDE;

    /* ------------------------------------------------------------------ */
    /*  Drink (drinkable column)                                            */
    /* ------------------------------------------------------------------ */

    @Test
    void strengthDrink_180s() {
        // OCM drinkable.strength = 180 s → 3600 t
        assertEquals(3600, PotionDurations.eraDurationTicks("strength", false, false, false));
    }

    @Test
    void strengthSplash_135s() {
        // OCM splash.strength = 135 s → 2700 t
        assertEquals(2700, PotionDurations.eraDurationTicks("strength", false, false, true));
    }

    @Test
    void strongStrengthDrink_90s() {
        // OCM drinkable.strong_strength = 90 s → 1800 t
        assertEquals(1800, PotionDurations.eraDurationTicks("strength", false, true, false));
    }

    @Test
    void longStrengthDrink_480s() {
        // OCM drinkable.long_strength = 480 s → 9600 t
        assertEquals(9600, PotionDurations.eraDurationTicks("strength", true, false, false));
    }

    @Test
    void swiftnessDrink_180s_aliasSpeed() {
        // OCM drinkable.swiftness = 180 s → 3600 t; "speed" is the modern alias.
        assertEquals(3600, PotionDurations.eraDurationTicks("swiftness", false, false, false));
        assertEquals(3600, PotionDurations.eraDurationTicks("speed", false, false, false));
    }

    @Test
    void fireResistanceDrink_180s() {
        // OCM drinkable.fire_resistance = 180 s → 3600 t
        assertEquals(3600, PotionDurations.eraDurationTicks("fire_resistance", false, false, false));
    }

    @Test
    void weaknessDrink_90s_noStrongVariant() {
        // OCM drinkable.weakness = 90 s → 1800 t. Weakness has no strong variant,
        // so upgraded falls back to the base value.
        assertEquals(1800, PotionDurations.eraDurationTicks("weakness", false, false, false));
        assertEquals(1800, PotionDurations.eraDurationTicks("weakness", false, true, false));
    }

    @Test
    void poisonSplash_33s() {
        // OCM splash.poison = 33 s → 660 t
        assertEquals(660, PotionDurations.eraDurationTicks("poison", false, false, true));
    }

    @Test
    void nightVisionSplash_equalsDrink_180s() {
        // OCM night_vision is 180 s for both drink and splash → 3600 t each.
        assertEquals(3600, PotionDurations.eraDurationTicks("night_vision", false, false, false));
        assertEquals(3600, PotionDurations.eraDurationTicks("night_vision", false, false, true));
    }

    @Test
    void slownessSplash_67s_aliasSlow() {
        // OCM splash.slowness = 67 s → 1340 t; "slow" is the modern alias.
        assertEquals(1340, PotionDurations.eraDurationTicks("slowness", false, false, true));
        assertEquals(1340, PotionDurations.eraDurationTicks("slow", false, false, true));
    }

    @Test
    void leapingDrink_180s_aliasJump() {
        // OCM drinkable.leaping = 180 s → 3600 t; "jump" is the modern alias.
        assertEquals(3600, PotionDurations.eraDurationTicks("leaping", false, false, false));
        assertEquals(3600, PotionDurations.eraDurationTicks("jump", false, false, false));
    }

    @Test
    void invisibilityDrink_180s() {
        // OCM drinkable.invisibility = 180 s → 3600 t
        assertEquals(3600, PotionDurations.eraDurationTicks("invisibility", false, false, false));
    }

    @Test
    void waterBreathingSplash_135s() {
        // OCM splash.water_breathing = 135 s → 2700 t
        assertEquals(2700, PotionDurations.eraDurationTicks("water_breathing", false, false, true));
    }

    /* ------------------------------------------------------------------ */
    /*  Instant + unknown → no override                                    */
    /* ------------------------------------------------------------------ */

    @Test
    void instantHealing_returnsNoOverride() {
        // Instant potions have no duration to restore — leave untouched.
        assertEquals(NONE, PotionDurations.eraDurationTicks("healing", false, false, false));
        assertEquals(NONE, PotionDurations.eraDurationTicks("instant_health", false, false, false));
    }

    @Test
    void instantHarming_returnsNoOverride() {
        assertEquals(NONE, PotionDurations.eraDurationTicks("harming", false, false, false));
        assertEquals(NONE, PotionDurations.eraDurationTicks("instant_damage", false, false, true));
    }

    @Test
    void waterAndUncraftable_returnNoOverride() {
        // No-effect base potions are not in the table.
        assertEquals(NONE, PotionDurations.eraDurationTicks("water", false, false, false));
        assertEquals(NONE, PotionDurations.eraDurationTicks("awkward", false, false, false));
        assertEquals(NONE, PotionDurations.eraDurationTicks("mundane", false, false, false));
    }

    @Test
    void unknownType_returnsNoOverride() {
        assertEquals(NONE, PotionDurations.eraDurationTicks("turbo_charge", false, false, false));
    }

    @Test
    void keyResolutionIsCaseAndNamespaceInsensitive() {
        // The module passes PotionType.name() (e.g. "STRENGTH") or a namespaced
        // key ("minecraft:strength"); both must resolve.
        assertEquals(3600, PotionDurations.eraDurationTicks("STRENGTH", false, false, false));
        assertEquals(3600, PotionDurations.eraDurationTicks("minecraft:strength", false, false, false));
    }

    @Test
    void prefixedTypeNamesResolveStrongAndLong() {
        // Pre-1.20 PotionData exposes flags separately, but legacy PotionType
        // enums fold the level into the name (STRONG_STRENGTH, LONG_STRENGTH).
        // Those must resolve to the upgraded / extended rows too.
        assertEquals(1800, PotionDurations.eraDurationTicks("STRONG_STRENGTH", false, false, false));
        assertEquals(9600, PotionDurations.eraDurationTicks("LONG_STRENGTH", false, false, false));
    }
}
