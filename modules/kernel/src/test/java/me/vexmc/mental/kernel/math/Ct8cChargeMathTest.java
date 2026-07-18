package me.vexmc.mental.kernel.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The Combat Test 8c attack-charge math (spec §2.1, code-confirmed against
 * {@code Player.getAttackStrengthScale}). The scale rises linearly to 200% of a
 * full delay that is twice {@code getAttackDelay()}, hitting 100% at the
 * halfway point; a landed hit needs full recharge, an air swing opens a lenient
 * 4-tick miss-recovery lane; the ≥195% gate feeds the charged reach bonus and
 * the sweep permission.
 */
class Ct8cChargeMathTest {

    private static final double EPSILON = 1.0e-9;

    /* ------------------------------- scale --------------------------------------------- */

    @Test
    void scaleReachesFullChargeAtHalfTheFullDelay() {
        // A sword: getAttackDelay() 7 ⇒ full delay 14; 100% at 7 ticks, 200% at 14.
        assertEquals(1.0, Ct8cChargeMath.scale(7, 14), EPSILON);
        assertEquals(2.0, Ct8cChargeMath.scale(14, 14), EPSILON);
        assertEquals(1.95, Ct8cChargeMath.scale(13.65, 14), EPSILON); // the 195% gate point
    }

    @Test
    void scaleClampsToTheZeroTwoRange() {
        assertEquals(0.0, Ct8cChargeMath.scale(0, 14), EPSILON);
        assertEquals(2.0, Ct8cChargeMath.scale(30, 14), EPSILON); // beyond full — clamped at 200%
    }

    /* ------------------------------- attack availability ------------------------------- */

    @Test
    void aLandedHitNeedsFullChargeButAirSwingOpensTheFourTickRecoveryLane() {
        assertTrue(Ct8cChargeMath.attackAllowed(1.0, false, 7), "scale 100% always attacks");
        assertFalse(Ct8cChargeMath.attackAllowed(0.5, false, 3), "below 100% with no recovery is denied");
        // Miss recovery: an air swing lets a re-attack through once >4 ticks elapsed.
        assertFalse(Ct8cChargeMath.attackAllowed(0.5, true, 4.0), "exactly 4 ticks is not yet past the gate");
        assertTrue(Ct8cChargeMath.attackAllowed(0.5, true, 4.5), "past 4 ticks the recovery lane opens");
    }

    /* ------------------------------- charged gates ------------------------------------- */

    @Test
    void chargedReachBonusNeedsAboveNinetyFivePercentAndNoCrouch() {
        assertTrue(Ct8cChargeMath.chargedBonus(1.96, false));
        assertFalse(Ct8cChargeMath.chargedBonus(1.95, false), "the gate is strictly greater than 1.95");
        assertFalse(Ct8cChargeMath.chargedBonus(2.0, true), "crouching denies the bonus");
    }

    @Test
    void sweepNeedsAboveNinetyFivePercentCharge() {
        assertTrue(Ct8cChargeMath.sweepAllowed(1.96));
        assertFalse(Ct8cChargeMath.sweepAllowed(1.95));
        assertFalse(Ct8cChargeMath.sweepAllowed(1.0));
    }
}
