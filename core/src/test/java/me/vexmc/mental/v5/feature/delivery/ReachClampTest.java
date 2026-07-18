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
        // scale 0.87 (the 2.4.5 default) → centre threshold 0.87*(3.0+0.4)=2.958, box
        // floor 0.87*3.0=2.61. Eye at chest-centre height, horizontal ray, so box = z - 0.3:
        // at z past 2.91 the box (z-0.3) already sits past the 2.61 floor, so the CENTRE
        // threshold is the binding bound — 2.95 passes (centre 2.95<=2.958), 2.97 is
        // rejected (centre 2.97>2.958 AND box 2.67>2.61).
        assertTrue(ReachClamp.passes(0, CENTER, 2.95, NO_HISTORY, 0, 0, 0, MAX_REACH, LENIENCY, 0.87));
        assertFalse(ReachClamp.passes(0, CENTER, 2.97, NO_HISTORY, 0, 0, 0, MAX_REACH, LENIENCY, 0.87));
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
        // The 2.4.4 fix, pinned at the 2.4.5 default scale. A STEEP honest answer:
        // attacker eye 4.0 directly above the victim column. eye-to-box (to the 1.8 top
        // face) = 2.2, INSIDE the honest floor scale*maxReach = 2.61; but eye-to-centre =
        // 4.0 - 0.9 = 3.1, well past 2.958. The centre-only backstop FALSE-REJECTED this
        // honest handicapped answer (the exact swing the handicap is documented to still
        // allow); the OR-pass box floor accepts it.
        assertEquals(2.2, ReachClamp.distanceToBox(0, 4.0, 0, 0, 0, 0), 1.0e-9);
        assertEquals(3.1, ReachClamp.distanceToCenter(0, 4.0, 0, 0, 0, 0), 1.0e-9);
        assertTrue(ReachClamp.passes(0, 4.0, 0, NO_HISTORY, 0, 0, 0, MAX_REACH, LENIENCY, 0.87),
                "a steep honest answer inside the box floor (2.2<=2.61) must pass despite centre 3.1>2.958");
    }

    @Test
    void handicapAcceptsTheHonestCapAndRejectsTheBlindOverReach() {
        // The honest handicapped cap at eye height (centre = scale*maxReach = 2.61 at the
        // 2.4.5 default) — accepted on the centre threshold (2.61<=2.958); its box distance
        // 2.21 also clears the 2.61 floor, so both bounds agree.
        double honestZ = Math.sqrt(0.87 * MAX_REACH * (0.87 * MAX_REACH) - V_GAP * V_GAP);
        assertEquals(0.87 * MAX_REACH,
                ReachClamp.distanceToCenter(0, EYE, honestZ, 0, 0, 0), 1.0e-9);
        assertTrue(ReachClamp.passes(0, EYE, honestZ, NO_HISTORY, 0, 0, 0, MAX_REACH, LENIENCY, 0.87),
                "the honest handicapped cap (centre = scale*maxReach) must pass");

        // The attribute-blind client's OWN gate edge (eye->chest-centre = maxReach = 3.0):
        // centre 3.0>2.958 AND its eye-to-box there (2.6123) exceeds the 2.61 floor, so the
        // floor does NOT rescue it and it is denied — the bite is intact. (At 0.87 the floor
        // 2.61 sits just below this 2.6123 edge; 0.8708 is where the floor would start
        // admitting it, so 0.87 keeps the bite with a ~0.0023-block margin.)
        double blindZ = Math.sqrt(MAX_REACH * MAX_REACH - V_GAP * V_GAP);
        assertEquals(MAX_REACH, ReachClamp.distanceToCenter(0, EYE, blindZ, 0, 0, 0), 1.0e-9);
        assertTrue(ReachClamp.distanceToBox(0, EYE, blindZ, 0, 0, 0) > 0.87 * MAX_REACH,
                "the blind edge's eye-to-box must exceed the honest floor (so the floor cannot rescue it)");
        assertFalse(ReachClamp.passes(0, EYE, blindZ, NO_HISTORY, 0, 0, 0, MAX_REACH, LENIENCY, 0.87),
                "the blind client's own reach edge (centre = maxReach) must be denied when handicapped");
    }

    @Test
    void theDefectFix_sameOverReachRejectedHandicappedButAcceptedPlain() {
        // A 2.7 eye-to-box over-reach (the 2.65..2.75 window the round names): z=3.0,
        // box = 2.7, eye-to-centre = 3.0852.
        double eyeZ = 3.0; // box distance = 3.0 - 0.3 = 2.7
        assertEquals(2.7, ReachClamp.distanceToBox(0, EYE, eyeZ, 0, 0, 0), 1.0e-9);

        // NEW (handicapped, eye-to-centre, threshold 2.958 at the 2.4.5 default): rejected
        // (centre 3.085>2.958 AND box 2.7>2.61 floor).
        assertFalse(ReachClamp.passes(0, EYE, eyeZ, NO_HISTORY, 0, 0, 0, MAX_REACH, LENIENCY, 0.87),
                "a 2.7 eye-to-box send must be rejected by the handicapped backstop");
        // PLAIN (handicap off): the byte-identical box window (3.4) accepts it.
        assertTrue(ReachClamp.passes(0, EYE, eyeZ, NO_HISTORY, 0, 0, 0, MAX_REACH, LENIENCY, null),
                "the same send must pass with the handicap off");

        // The DEFECT it fixes: the audit's clamp was eye-to-box scale*maxReach +
        // leniency = 0.87*3.0 + 0.4 = 3.01 — ABOVE the 2.7 box distance, so it waved
        // this over-reach through. Pinned so a regression to the box shape is caught.
        double oldClamp = 0.87 * MAX_REACH + LENIENCY; // 3.01, eye-to-box
        assertTrue(ReachClamp.distanceToBox(0, EYE, eyeZ, 0, 0, 0) <= oldClamp,
                "the old eye-to-box clamp (3.01) sat above the 2.7 over-reach — the no-op the fix closes");
    }

    /* ----------------------- victim-AABB inflation (CT8c §2.11, wire 2c) ----------------------- */

    @Test
    void inflatingTheVictimBoxAcceptsAPlainHitThatMissesByPointOneFour() {
        // The CT8c targeting assist mirrors the kernel ReachValidator inflation: victim
        // feet 3.84 out on +z, the plain 0.6-wide box faces at 3.54 (0.14 beyond the 3.4
        // window) MISSES; inflated to 0.9 (half 0.45) the face is 3.39, inside 3.4.
        assertFalse(ReachClamp.passes(0, EYE, 3.84, NO_HISTORY, 0, 0, 0, MAX_REACH, LENIENCY, null),
                "the 0.6-wide box misses by 0.14");
        assertTrue(ReachClamp.passes(0, EYE, 3.84, NO_HISTORY, 0, 0, 0, MAX_REACH, LENIENCY, null, 0.9),
                "inflating the box to 0.9 lands the same hit");
    }

    @Test
    void theInflationOverloadDefaultsToNoInflationByteIdentically() {
        // The new width overload with 0.0 (or the native 0.6) reproduces the plain and
        // handicap windows exactly — the zero-touch default when ct8c-reach is off.
        assertFalse(ReachClamp.passes(0, EYE, 3.84, NO_HISTORY, 0, 0, 0, MAX_REACH, LENIENCY, null, 0.0));
        assertFalse(ReachClamp.passes(0, EYE, 3.84, NO_HISTORY, 0, 0, 0, MAX_REACH, LENIENCY, null, 0.6));
        // Handicap path unchanged at width 0.0: the 2.7 over-reach the backstop rejects.
        assertFalse(ReachClamp.passes(0, EYE, 3.0, NO_HISTORY, 0, 0, 0, MAX_REACH, LENIENCY, 0.87, 0.0));
    }

    @Test
    void handicapHonoursTheClosestRewoundCandidate() {
        // Attacker eye at the origin; the victim's live position has fled to 8 blocks
        // out (far), but a rewound sample was inside the 2.958 handicapped window —
        // the closest candidate decides (the ClubSpigot-lite leniency the plain shape
        // keeps too). Sample at z=2.0: centre = sqrt(2^2 + 0.72^2) = 2.1256.
        List<PositionRing.Sample> history = List.of(
                new PositionRing.Sample(0, 0, 7.5, 1L),
                new PositionRing.Sample(0, 0, 2.0, 2L));
        assertTrue(ReachClamp.passes(0, EYE, 0, history, 0, 0, 8, MAX_REACH, LENIENCY, 0.87),
                "a historical candidate within the handicapped window passes");

        // With every candidate beyond the window, it fails.
        List<PositionRing.Sample> allFar = List.of(
                new PositionRing.Sample(0, 0, 5.0, 1L),
                new PositionRing.Sample(0, 0, 6.0, 2L));
        assertFalse(ReachClamp.passes(0, EYE, 0, allFar, 0, 0, 8, MAX_REACH, LENIENCY, 0.87));
    }
}
