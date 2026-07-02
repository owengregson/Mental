package me.vexmc.mental.kernel.coexist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The startup warnings the arbiter emits (mandate §4.11): the two feel-burying
 * OCM defaults, plus one line per Mental-owned rule that is also enabled in
 * OCM (a silent double-apply the operator must resolve). Arbitrated tokens
 * never produce a double-enable line — they coordinate through the arbiter.
 */
class CoexistWarningsTest {

    private static CoexistWarnings.OcmFacts facts(
            boolean present, boolean modeset, Integer playerDelay, String... enabledKeys) {
        return new CoexistWarnings.OcmFacts(present, modeset, playerDelay, Set.of(enabledKeys));
    }

    @Test
    void ocmAbsentYieldsNoWarnings() {
        List<String> warnings = CoexistWarnings.derive(
                facts(false, true, 18, "old-player-regen"),
                EnumSet.of(MechanicToken.REGEN));
        assertTrue(warnings.isEmpty(), () -> "absent OCM must warn about nothing: " + warnings);
    }

    @Test
    void defaultModesetKnockbackWarns() {
        List<String> warnings = CoexistWarnings.derive(
                facts(true, true, 20), EnumSet.noneOf(MechanicToken.class));
        assertEquals(1, warnings.size(), () -> warnings.toString());
        assertTrue(warnings.get(0).contains("old-player-knockback"));
    }

    @Test
    void playerDelayWarnsOnAnyValueOtherThanTwenty() {
        // The OCM default 18 warns.
        List<String> defaulted = CoexistWarnings.derive(
                facts(true, false, 18), EnumSet.noneOf(MechanicToken.class));
        assertEquals(1, defaulted.size());
        assertTrue(defaulted.get(0).contains("18"));

        // Any other non-20 value warns too.
        assertEquals(1, CoexistWarnings.derive(
                facts(true, false, 25), EnumSet.noneOf(MechanicToken.class)).size());

        // Exactly 20 (the era truth) does not warn.
        assertTrue(CoexistWarnings.derive(
                facts(true, false, 20), EnumSet.noneOf(MechanicToken.class)).isEmpty());

        // Unknown (null) playerDelay cannot be compared → no warning.
        assertTrue(CoexistWarnings.derive(
                facts(true, false, null), EnumSet.noneOf(MechanicToken.class)).isEmpty());
    }

    @Test
    void oneDoubleEnableLinePerOverlappingMentalOwnedRule() {
        // Mental enables regen + sweep; OCM has both modules on → two lines.
        List<String> warnings = CoexistWarnings.derive(
                facts(true, false, 20, "old-player-regen", "disable-sword-sweep", "disable-offhand"),
                EnumSet.of(MechanicToken.REGEN, MechanicToken.SWEEP));
        assertEquals(2, warnings.size(), () -> warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("old-player-regen")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("disable-sword-sweep")));
        // disable-offhand is on in OCM but Mental did not enable OFFHAND → no line.
        assertTrue(warnings.stream().noneMatch(w -> w.contains("disable-offhand")));
    }

    @Test
    void arbitratedTokenNeverProducesADoubleEnableLine() {
        // Both enable melee KB; that is arbitrated, so no double-enable line —
        // the modeset warning (separately) is what covers old-player-knockback.
        List<String> warnings = CoexistWarnings.derive(
                facts(true, false, 20, "old-player-knockback"),
                EnumSet.of(MechanicToken.MELEE_KNOCKBACK));
        assertTrue(warnings.isEmpty(), () -> "arbitrated tokens coordinate, never warn: " + warnings);
    }

    @Test
    void allThreeWarningClassesCombine() {
        List<String> warnings = CoexistWarnings.derive(
                facts(true, true, 18, "old-golden-apples"),
                EnumSet.of(MechanicToken.GOLDEN_APPLES));
        // modeset + playerDelay + one double-enable = 3.
        assertEquals(3, warnings.size(), () -> warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("old-player-knockback")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("18")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("old-golden-apples")));
    }

    @Test
    void sharedOcmKeyMatchesEitherMentalToken() {
        // Potion durations + values both map to OCM's old-potion-effects. Enabling
        // either in Mental while OCM has old-potion-effects on → a double-enable line.
        List<String> durations = CoexistWarnings.derive(
                facts(true, false, 20, "old-potion-effects"),
                EnumSet.of(MechanicToken.POTION_DURATIONS));
        assertEquals(1, durations.size());
        assertTrue(durations.get(0).contains("old-potion-effects"));
    }
}
