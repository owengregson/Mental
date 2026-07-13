package me.vexmc.mental.v5.feature.feedback;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins vanilla's mid-invulnerability UPGRADE-branch predicate — the one shared
 * source {@link HitFeedbackListener}'s era-silence and
 * {@link DamageIndicatorsListener}'s window folding both read, so they can never
 * drift. The strict {@code >} is load-bearing: a boundary-adopted fast-path hit
 * sets {@code noDamageTicks} to EXACTLY {@code max/2}, which must read as FRESH
 * (never a phantom delta). The {@code max/2} is integer division — the server's
 * own arithmetic verbatim.
 */
class UpgradeWindowTest {

    @Test
    void aboveHalfIsADelta() {
        assertTrue(UpgradeWindow.isDelta(11, 20));
        assertTrue(UpgradeWindow.isDelta(20, 20)); // a just-re-armed window
        assertTrue(UpgradeWindow.isDelta(19, 20));
    }

    @Test
    void exactlyHalfIsFresh() {
        // The boundary-adopted hit sets nd = max/2 (HitRegistrationUnit#adoptBoundary);
        // the strict > must treat it as FRESH so an adopted hit never hides.
        assertFalse(UpgradeWindow.isDelta(10, 20));
    }

    @Test
    void belowHalfIsFresh() {
        assertFalse(UpgradeWindow.isDelta(9, 20));
        assertFalse(UpgradeWindow.isDelta(0, 20)); // a fully-decayed / cleared window
    }

    @Test
    void integerDivisionMatchesTheServer() {
        // An odd max: max/2 truncates to 9 (int division), so nd=9 is FRESH, nd=10 is a delta.
        assertFalse(UpgradeWindow.isDelta(9, 19));
        assertTrue(UpgradeWindow.isDelta(10, 19));
    }

    @Test
    void aShorterLegacyPhaseUsesTheSameFormula() {
        // The legacy tier double-ticks the counter, so its effective phase is shorter —
        // but the predicate is length-agnostic: it is always nd > max/2 of whatever max is.
        assertTrue(UpgradeWindow.isDelta(6, 10));
        assertFalse(UpgradeWindow.isDelta(5, 10));
    }
}
