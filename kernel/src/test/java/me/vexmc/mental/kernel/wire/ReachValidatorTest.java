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

    /* ------------------- victim-AABB inflation (CT8c §2.11, Task INT wire 2c) ------------------- */

    @Test
    void inflatingTheVictimBoxAcceptsAHitThatMissesByPointOneFour() {
        // The CT8c targeting assist (spec §2.11): entities under 0.9 blocks wide are
        // inflated to 0.9 for attack targeting. Victim feet 3.84 out on +z; the plain
        // 0.6-wide box (half 0.3) puts the face at 3.54 — 0.14 beyond the 3.4 window,
        // so it MISSES today. Inflated to 0.9 (half 0.45) the face is at 3.39, inside
        // 3.4, so the same hit lands.
        ReachValidator.Verdict plain = ReachValidator.validate(
                0, EYE, 0, List.of(), 0, 0, 3.84, 3.0, 0.4);
        assertFalse(plain.valid(), "the 0.6-wide box misses by 0.14 (3.54 > 3.4)");
        assertEquals(3.54, plain.bestDistance(), 1.0e-9);

        ReachValidator.Verdict inflated = ReachValidator.validate(
                0, EYE, 0, List.of(), 0, 0, 3.84, 3.0, 0.4, 0.9);
        assertTrue(inflated.valid(), "inflating the box to 0.9 brings the face to 3.39, inside 3.4");
        assertEquals(3.39, inflated.bestDistance(), 1.0e-9);
    }

    @Test
    void theInflationParameterDefaultsToNoInflationByteIdentically() {
        // The additive overload with width 0.0 (and any width <= 0.6, the native box)
        // is byte-identical to the pre-inflation validate — the zero-touch default.
        ReachValidator.Verdict legacy = ReachValidator.validate(
                0, EYE, 0, List.of(), 0, 0, 3.84, 3.0, 0.4);
        ReachValidator.Verdict noInflation = ReachValidator.validate(
                0, EYE, 0, List.of(), 0, 0, 3.84, 3.0, 0.4, 0.0);
        ReachValidator.Verdict nativeWidth = ReachValidator.validate(
                0, EYE, 0, List.of(), 0, 0, 3.84, 3.0, 0.4, 0.6);
        assertEquals(legacy.bestDistance(), noInflation.bestDistance(), 1.0e-12);
        assertEquals(legacy.bestDistance(), nativeWidth.bestDistance(), 1.0e-12);
        assertEquals(legacy.valid(), noInflation.valid());
        assertEquals(legacy.valid(), nativeWidth.valid());
    }
}
