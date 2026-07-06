package me.vexmc.mental.v5.feature.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.vexmc.mental.kernel.wire.PositionRing;
import org.junit.jupiter.api.Test;

/**
 * The reach-clamp arithmetic, both shapes (servo-lab 243 S2). The plain shape is
 * pinned byte-identical to the kernel {@code ReachValidator}'s documented boundary
 * values (so the non-handicap fast path is unchanged); the handicap shape is pinned
 * to its scaled eye-to-centre window, including the exact defect the round exposed:
 * an attribute-blind client's over-reach that the audit's eye-to-box clamp waved
 * through is now rejected, while the honest handicapped cap keeps a comfortable
 * margin and the plain window still accepts the same geometry.
 */
class ReachClampTest {

    private static final double EYE = ReachClamp.EYE_HEIGHT;   // 1.62
    private static final double CENTER = ReachClamp.CENTER_HEIGHT; // 0.9
    private static final double V_GAP = EYE - CENTER;          // 0.72, eye above chest centre

    // The shipped reach-validation defaults.
    private static final double MAX_REACH = 3.0;
    private static final double LENIENCY = 0.4;

    private static final List<PositionRing.Sample> NO_HISTORY = List.of();

    /* ----------------------------- the two geometries ----------------------------- */

    @Test
    void distanceToBoxReproducesTheKernelBoundaryValues() {
        // The exact pins the kernel ReachValidatorTest asserts — proving the core
        // copy is byte-identical, so the plain (non-handicap) path is unchanged.
        assertEquals(3.7, ReachClamp.distanceToBox(0, EYE, 4, 0, 0, 0), 1.0e-9); // face at z=0.3, 4 away
        assertEquals(0.0, ReachClamp.distanceToBox(0, 1.0, 0, 0, 0, 0), 1.0e-9); // inside the box
        assertEquals(0.7, ReachClamp.distanceToBox(0, 2.5, 0, 0, 0, 0), 1.0e-9); // above the head plane
        assertEquals(2.7, ReachClamp.distanceToBox(0, EYE, 3, 0, 0, 0), 1.0e-9); // face at z=0.3, 3 away
    }

    @Test
    void distanceToCenterIsEyeToChestCentre() {
        // Eye at chest-centre height, straight out +z: the centre distance is the raw z.
        assertEquals(3.0, ReachClamp.distanceToCenter(0, CENTER, 3, 0, 0, 0), 1.0e-9);
        // Straight up from the feet: only the eye-above-centre gap.
        assertEquals(V_GAP, ReachClamp.distanceToCenter(0, EYE, 0, 0, 0, 0), 1.0e-9);
        // Realistic eye 3 blocks out: sqrt(3^2 + 0.72^2) = sqrt(9.5184).
        assertEquals(3.0851904, ReachClamp.distanceToCenter(0, EYE, 3, 0, 0, 0), 1.0e-6);
    }

    /* ----------------------- the plain window (byte-identical) --------------------- */

    @Test
    void plainWindowIsEyeToBoxWithTheFullUnscaledLeniency() {
        // Threshold = maxReach + leniency = 3.4 eye-to-box; the box face sits at z-0.3.
        // z=3.65 → box 3.35, inside; z=3.75 → box 3.45, outside — the threshold is 3.4.
        assertTrue(ReachClamp.passes(0, EYE, 3.65, NO_HISTORY, 0, 0, 0, MAX_REACH, LENIENCY, null));
        assertFalse(ReachClamp.passes(0, EYE, 3.75, NO_HISTORY, 0, 0, 0, MAX_REACH, LENIENCY, null));
    }

    /* ----------------------- the handicap window (the backstop) -------------------- */

    @Test
    void handicapCentreThresholdBindsWhenTheBoxFloorIsInside() {
        // scale 0.8 → centre threshold 0.8*(3.0+0.4)=2.72, box floor 0.8*3.0=2.4.
        // Eye at chest-centre height, horizontal ray, so box = z - 0.3: at z past 2.72
        // the box (2.42) already sits past the 2.4 floor, so the CENTRE threshold is the
        // binding bound — 2.71 passes (centre 2.71<=2.72), 2.73 is rejected (centre
        // 2.73>2.72 AND box 2.43>2.4).
        assertTrue(ReachClamp.passes(0, CENTER, 2.71, NO_HISTORY, 0, 0, 0, MAX_REACH, LENIENCY, 0.8));
        assertFalse(ReachClamp.passes(0, CENTER, 2.73, NO_HISTORY, 0, 0, 0, MAX_REACH, LENIENCY, 0.8));
    }

    @Test
    void handicapBoxFloorIsTheLooserBoundWhenItExceedsTheCentreThreshold() {
        // scale 0.6 → centre threshold 0.6*3.4=2.04, box floor 0.6*3.0=1.8. For the same
        // chest-height horizontal ray (box = z - 0.3) the honest box floor is the LOOSER
        // (outer) bound: box reaches 1.8 at z=2.1, past the 2.04 centre — so the FLOOR
        // decides. z=2.09 passes (box 1.79<=1.8), z=2.11 is rejected (box 1.81>1.8 AND
        // centre 2.11>2.04). (The pre-floor centre-only clamp wrongly rejected z in
        // (2.04, 2.10] — honest handicapped answers inside the 1.8 hit-point envelope.)
        assertTrue(ReachClamp.passes(0, CENTER, 2.09, NO_HISTORY, 0, 0, 0, MAX_REACH, LENIENCY, 0.6));
        assertFalse(ReachClamp.passes(0, CENTER, 2.11, NO_HISTORY, 0, 0, 0, MAX_REACH, LENIENCY, 0.6));
    }

    @Test
    void handicapHonestBoxFloorPassesASteepAnswerDespiteInflatedCentre() {
        // The 2.4.4 fix. A STEEP honest answer: attacker eye 4.0 directly above the
        // victim column. eye-to-box (to the 1.8 top face) = 2.2, INSIDE the honest floor
        // scale*maxReach = 2.4; but eye-to-centre = 4.0 - 0.9 = 3.1, well past 2.72. The
        // centre-only backstop FALSE-REJECTED this honest handicapped answer (the exact
        // swing the handicap is documented to still allow); the OR-pass box floor accepts it.
        assertEquals(2.2, ReachClamp.distanceToBox(0, 4.0, 0, 0, 0, 0), 1.0e-9);
        assertEquals(3.1, ReachClamp.distanceToCenter(0, 4.0, 0, 0, 0, 0), 1.0e-9);
        assertTrue(ReachClamp.passes(0, 4.0, 0, NO_HISTORY, 0, 0, 0, MAX_REACH, LENIENCY, 0.8),
                "a steep honest answer inside the box floor (2.2<=2.4) must pass despite centre 3.1>2.72");
    }

    @Test
    void handicapAcceptsTheHonestCapAndRejectsTheBlindOverReach() {
        // The honest handicapped cap at eye height (centre = scale*maxReach = 2.4) —
        // accepted on the centre threshold (2.4<=2.72); its box distance 1.99 also clears
        // the 2.4 floor, so both bounds agree.
        double honestZ = Math.sqrt(0.8 * MAX_REACH * (0.8 * MAX_REACH) - V_GAP * V_GAP);
        assertEquals(0.8 * MAX_REACH,
                ReachClamp.distanceToCenter(0, EYE, honestZ, 0, 0, 0), 1.0e-9);
        assertTrue(ReachClamp.passes(0, EYE, honestZ, NO_HISTORY, 0, 0, 0, MAX_REACH, LENIENCY, 0.8),
                "the honest handicapped cap (centre = scale*maxReach) must pass");

        // The attribute-blind client's OWN gate edge (eye->chest-centre = maxReach = 3.0):
        // centre 3.0>2.72 AND its eye-to-box there (2.6124) exceeds the 2.4 floor, so the
        // floor does NOT rescue it and it is denied — the bite is intact.
        double blindZ = Math.sqrt(MAX_REACH * MAX_REACH - V_GAP * V_GAP);
        assertEquals(MAX_REACH, ReachClamp.distanceToCenter(0, EYE, blindZ, 0, 0, 0), 1.0e-9);
        assertTrue(ReachClamp.distanceToBox(0, EYE, blindZ, 0, 0, 0) > 0.8 * MAX_REACH,
                "the blind edge's eye-to-box must exceed the honest floor (so the floor cannot rescue it)");
        assertFalse(ReachClamp.passes(0, EYE, blindZ, NO_HISTORY, 0, 0, 0, MAX_REACH, LENIENCY, 0.8),
                "the blind client's own reach edge (centre = maxReach) must be denied when handicapped");
    }

    @Test
    void theDefectFix_sameOverReachRejectedHandicappedButAcceptedPlain() {
        // A 2.7 eye-to-box over-reach (the 2.65..2.75 window the round names): z=3.0,
        // box = 2.7, eye-to-centre = 3.0852.
        double eyeZ = 3.0; // box distance = 3.0 - 0.3 = 2.7
        assertEquals(2.7, ReachClamp.distanceToBox(0, EYE, eyeZ, 0, 0, 0), 1.0e-9);

        // NEW (handicapped, eye-to-centre, threshold 2.72): rejected.
        assertFalse(ReachClamp.passes(0, EYE, eyeZ, NO_HISTORY, 0, 0, 0, MAX_REACH, LENIENCY, 0.8),
                "a 2.7 eye-to-box send must be rejected by the handicapped backstop");
        // PLAIN (handicap off): the byte-identical box window (3.4) accepts it.
        assertTrue(ReachClamp.passes(0, EYE, eyeZ, NO_HISTORY, 0, 0, 0, MAX_REACH, LENIENCY, null),
                "the same send must pass with the handicap off");

        // The DEFECT it fixes: the audit's clamp was eye-to-box scale*maxReach +
        // leniency = 0.8*3.0 + 0.4 = 2.8 — ABOVE the 2.7 box distance, so it waved
        // this over-reach through. Pinned so a regression to the box shape is caught.
        double oldClamp = 0.8 * MAX_REACH + LENIENCY; // 2.8, eye-to-box
        assertTrue(ReachClamp.distanceToBox(0, EYE, eyeZ, 0, 0, 0) <= oldClamp,
                "the old eye-to-box clamp (2.8) sat above the 2.7 over-reach — the no-op the fix closes");
    }

    @Test
    void handicapHonoursTheClosestRewoundCandidate() {
        // Attacker eye at the origin; the victim's live position has fled to 8 blocks
        // out (far), but a rewound sample was inside the 2.72 handicapped window —
        // the closest candidate decides (the ClubSpigot-lite leniency the plain shape
        // keeps too). Sample at z=2.0: centre = sqrt(2^2 + 0.72^2) = 2.1256.
        List<PositionRing.Sample> history = List.of(
                new PositionRing.Sample(0, 0, 7.5, 1L),
                new PositionRing.Sample(0, 0, 2.0, 2L));
        assertTrue(ReachClamp.passes(0, EYE, 0, history, 0, 0, 8, MAX_REACH, LENIENCY, 0.8),
                "a historical candidate within the handicapped window passes");

        // With every candidate beyond the window, it fails.
        List<PositionRing.Sample> allFar = List.of(
                new PositionRing.Sample(0, 0, 5.0, 1L),
                new PositionRing.Sample(0, 0, 6.0, 2L));
        assertFalse(ReachClamp.passes(0, EYE, 0, allFar, 0, 0, 8, MAX_REACH, LENIENCY, 0.8));
    }
}
