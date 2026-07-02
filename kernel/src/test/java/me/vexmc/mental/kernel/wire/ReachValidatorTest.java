package me.vexmc.mental.kernel.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReachValidatorTest {

    private static final double EYE = ReachValidator.EYE_HEIGHT;

    @Test
    void distanceIsEyeToClosestBoxPoint() {
        // Victim feet at origin; eye level inside the box's vertical span,
        // 4 blocks down +z: closest point is the box face at z = 0.3.
        assertEquals(3.7, ReachValidator.distanceToBox(0, EYE, 4, 0, 0, 0), 1.0e-9);
        // Inside the box: zero.
        assertEquals(0.0, ReachValidator.distanceToBox(0, 1.0, 0, 0, 0, 0), 1.0e-9);
        // Above the box: vertical distance from the head plane.
        assertEquals(0.7, ReachValidator.distanceToBox(0, 2.5, 0, 0, 0, 0), 1.0e-9);
    }

    @Test
    void liveCandidateAloneDecidesWhenHistoryIsEmpty() {
        // 3.7 to the face — beyond 3.0 + 0.4.
        ReachValidator.Verdict tooFar = ReachValidator.validate(
                0, EYE, 0, List.of(), 0, 0, 4, 3.0, 0.4);
        assertFalse(tooFar.valid());
        assertEquals(3.7, tooFar.bestDistance(), 1.0e-9);

        // 2.7 to the face — comfortably within.
        ReachValidator.Verdict inReach = ReachValidator.validate(
                0, EYE, 0, List.of(), 0, 0, 3, 3.0, 0.4);
        assertTrue(inReach.valid());
    }

    @Test
    void anyHistoricalCandidateWithinReachPasses() {
        // Live position has fled to 8 blocks, but the rewound instant says
        // the victim was 3 blocks away when the attacker swung — valid.
        List<PositionRing.Sample> history = List.of(
                new PositionRing.Sample(0, 0, 7.5, 1L),
                new PositionRing.Sample(0, 0, 3.0, 2L));
        ReachValidator.Verdict verdict = ReachValidator.validate(
                0, EYE, 0, history, 0, 0, 8, 3.0, 0.4);
        assertTrue(verdict.valid());
        assertEquals(2.7, verdict.bestDistance(), 1.0e-9);
    }

    @Test
    void beyondReachAtEveryCandidateFails() {
        List<PositionRing.Sample> history = List.of(
                new PositionRing.Sample(0, 0, 5.0, 1L),
                new PositionRing.Sample(0, 0, 6.0, 2L));
        ReachValidator.Verdict verdict = ReachValidator.validate(
                0, EYE, 0, history, 0, 0, 7, 3.0, 0.4);
        assertFalse(verdict.valid());
        assertEquals(4.7, verdict.bestDistance(), 1.0e-9);
    }

    @Test
    void raisedAttackReachAttributeWidensTheGate() {
        // 4.7 blocks to the face: invalid at 3.0 but valid when the server
        // raised entity-interaction-range to 4.5.
        ReachValidator.Verdict vanilla = ReachValidator.validate(
                0, EYE, 0, List.of(), 0, 0, 5, 3.0, 0.4);
        assertFalse(vanilla.valid());
        ReachValidator.Verdict extended = ReachValidator.validate(
                0, EYE, 0, List.of(), 0, 0, 5, 4.5, 0.4);
        assertTrue(extended.valid());
    }
}
