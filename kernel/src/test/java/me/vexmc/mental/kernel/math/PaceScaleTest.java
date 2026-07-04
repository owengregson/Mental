package me.vexmc.mental.kernel.math;

import static org.junit.jupiter.api.Assertions.assertEquals;

import me.vexmc.mental.kernel.model.EntityState;
import me.vexmc.mental.kernel.profile.PaceScaling;
import org.junit.jupiter.api.Test;

/**
 * Hand-computed pins for the pace factor {@code s = clamp(min, max, (attr /
 * baseline)^exponent)} (speed-conformal knockback, design 2026-07-04). The
 * measured baselines are sprint 0.13 (0.1 base × the 1.3 sprint modifier) and
 * walk 0.10 (assumption A2, javap-verified 2026-07-04). Every arithmetic step is
 * stated in the assertion so a value can never drift silently.
 */
class PaceScaleTest {

    private static final double EPSILON = 1.0e-9;
    private static final PaceScaling ATTACKER = new PaceScaling(PaceScaling.Mode.ATTACKER, 1.0, 0.5, 2.0);

    @Test
    void offModeIsExactlyOneRegardlessOfAttribute() {
        // Byte-identity skip: mode off yields exactly 1.0 for any attribute,
        // and the engine then multiplies by nothing.
        assertEquals(1.0, PaceScale.factor(0.208, true, PaceScaling.OFF), 0.0);
        assertEquals(1.0, PaceScale.factor(0.05, false, PaceScaling.OFF), 0.0);
    }

    @Test
    void baseSprintAndBaseWalkAreExactlyOne() {
        // 0.13 / 0.13 = 1.0 (sprint); 0.10 / 0.10 = 1.0 (walk) — plain
        // base-speed play ships the era stamp byte-identically.
        assertEquals(1.0, PaceScale.factor(PaceScale.SPRINT_BASELINE, true, ATTACKER), 0.0);
        assertEquals(1.0, PaceScale.factor(PaceScale.WALK_BASELINE, false, ATTACKER), 0.0);
    }

    @Test
    void speedThreeIsOnePointSixSprintingAndWalking() {
        // Speed III sprint: 0.1 × 1.3 × 1.6 = 0.208; 0.208 / 0.13 = 1.6.
        assertEquals(1.6, PaceScale.factor(0.208, true, ATTACKER), EPSILON);
        // Speed III walk: 0.1 × 1.6 = 0.16; 0.16 / 0.10 = 1.6.
        assertEquals(1.6, PaceScale.factor(0.16, false, ATTACKER), EPSILON);
    }

    @Test
    void slownessGivesLessThanOne() {
        // Slowness II sprint: 0.1 × 1.3 × 0.7 = 0.091; 0.091 / 0.13 = 0.7.
        assertEquals(0.7, PaceScale.factor(0.091, true, ATTACKER), EPSILON);
    }

    @Test
    void clampsBindAtTheWindowEdges() {
        // Above max: 0.5 / 0.13 = 3.846… → clamped to max 2.0.
        assertEquals(2.0, PaceScale.factor(0.5, true, ATTACKER), EPSILON);
        // Below min: 0.03 / 0.13 = 0.230… → clamped to min 0.5.
        assertEquals(0.5, PaceScale.factor(0.03, true, ATTACKER), EPSILON);
    }

    @Test
    void exponentTempersTheFactor() {
        // exponent 0.5: sqrt(0.208 / 0.13) = sqrt(1.6) = 1.264911… (inside the
        // clamp window), so the half-exponent softens 1.6 toward 1.0.
        PaceScaling tempered = new PaceScaling(PaceScaling.Mode.ATTACKER, 0.5, 0.5, 2.0);
        assertEquals(Math.sqrt(1.6), PaceScale.factor(0.208, true, tempered), EPSILON);
    }

    @Test
    void unavailableAttributeResolvesToTheStanceBaseline() {
        // The sentinel (and any non-positive attribute) ⇒ the baseline ⇒ 1.0,
        // never silently something else.
        assertEquals(1.0, PaceScale.factor(EntityState.MOVE_SPEED_UNAVAILABLE, true, ATTACKER), 0.0);
        assertEquals(1.0, PaceScale.factor(EntityState.MOVE_SPEED_UNAVAILABLE, false, ATTACKER), 0.0);
        assertEquals(1.0, PaceScale.factor(0.0, true, ATTACKER), 0.0);
    }
}
