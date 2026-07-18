package me.vexmc.mental.kernel.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The Combat Test 8c shield math (spec §2.6, code-confirmed against {@code
 * LivingEntity.isDamageSourceBlocked}/{@code ShieldItem}): the ≈148° frontal
 * arc (the {@code dot·π < −0.8726646} test), the hardcoded 5.0 melee cap with
 * excess passthrough, and the axe-disable duration {@code 1.6s + 0.5s·Cleaving}.
 */
class Ct8cShieldMathTest {

    private static final double EPSILON = 1.0e-9;

    /* ------------------------------- arc --------------------------------------------- */

    @Test
    void theArcLimitIsMinusFiftyDegreesInRadians() {
        // −0.8726646304130554 is the decompiled float −0.8726646f widened to double.
        assertEquals(-0.8726646304130554, Ct8cShieldMath.ARC_DOT_LIMIT, 0.0);
    }

    @Test
    void headOnHitIsInsideTheBlockingArc() {
        // Victim facing the attacker: viewDir and attacker→victim are anti-parallel,
        // dot ≈ −1, dot·π ≈ −π ≈ −3.14 < the limit ⇒ blocked.
        assertTrue(Ct8cShieldMath.withinArc(-Math.PI));
    }

    @Test
    void ninetyDegreeHitIsOutsideTheArc() {
        // Perpendicular look: dot ≈ 0, dot·π = 0, not below the limit ⇒ not blocked.
        assertFalse(Ct8cShieldMath.withinArc(0.0));
    }

    @Test
    void exactlyAtTheArcLimitIsNotBlocked() {
        // The test is strictly less-than, so the boundary itself passes through.
        assertFalse(Ct8cShieldMath.withinArc(Ct8cShieldMath.ARC_DOT_LIMIT));
        assertTrue(Ct8cShieldMath.withinArc(Ct8cShieldMath.ARC_DOT_LIMIT - 1.0e-6));
    }

    /* ------------------------------- 5-cap passthrough --------------------------------- */

    @Test
    void theMeleeCapBlocksAtMostFiveAndLetsTheRestThrough() {
        assertEquals(5.0, Ct8cShieldMath.blockedPortion(7.0), EPSILON); // 7 dmg → 5 blocked, 2 through
        assertEquals(3.0, Ct8cShieldMath.blockedPortion(3.0), EPSILON); // under the cap → all blocked
        assertEquals(5.0, Ct8cShieldMath.blockedPortion(5.0), EPSILON); // exactly the cap
    }

    /* ------------------------------- axe disable --------------------------------------- */

    @Test
    void axeDisableIsThirtyTwoTicksPlusTenPerCleavingLevel() {
        assertEquals(32, Ct8cShieldMath.axeDisableTicks(0)); // 1.6s
        assertEquals(42, Ct8cShieldMath.axeDisableTicks(1));
        assertEquals(52, Ct8cShieldMath.axeDisableTicks(2));
        assertEquals(62, Ct8cShieldMath.axeDisableTicks(3)); // 1.6s + 1.5s
    }
}
