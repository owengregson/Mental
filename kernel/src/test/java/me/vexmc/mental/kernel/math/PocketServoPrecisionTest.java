package me.vexmc.mental.kernel.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins for the precision-round pocket servo (combo-hold §3.2b;
 * precision-derivation §7.1; the 2.4.5 answer-denial-boundary redesign). The
 * load-bearing test is {@link #gridLandsExactlyOnTarget()}: across the grounded/air
 * launch branches, vertical stamps, separations, residuals, drift and ping cells the
 * derivation names, the raw σ* the solve returns — fed through an INDEPENDENT
 * tick-by-tick era flight fold (never the production sum code) — lands the victim
 * within 1e-9 of the resolved target. The closed forms (D_g / D_a / S2 / ground
 * locomotion), the answer-denial-boundary geometry, the ping-shift arithmetic, the
 * ice decline and the degenerate guards are hand-pinned separately so the fold can
 * never drift from the physics it inverts.
 */
class PocketServoPrecisionTest {

    private static final double EPSILON = 1.0e-9;
    private static final double Q = Decay.AIR_DRAG; // 0.91
    private static final PocketServoConfig SERVO = PocketServoConfig.of(2.75, 1.0, 0.8, 1.2, 10);

    /* ── closed forms hand-checked against the derivation numbers ──────────── */

    @Test
    void closedFormsMatchTheDerivation() {
        // geo(10), D_g(10) @ stone, S2(10) — the §1.2/§2.2 numbers, checked against
        // independent brute sums AND the derivation's printed values (loose, its own
        // rounding).
        double sqStone = 0.6 * Q; // 0.546
        assertEquals(bruteGeo(10), PocketServo.geo(10), EPSILON);
        assertEquals(6.784265, PocketServo.geo(10), 1.0e-4, "geo(10) ≈ 6.784265");
        assertEquals(1.0 + sqStone * bruteGeo(9), PocketServo.groundedLaunchDragSum(10, sqStone), EPSILON);
        assertEquals(4.470559, PocketServo.groundedLaunchDragSum(10, sqStone), 1.0e-3, "D_g(10) ≈ 4.470559");
        assertEquals(bruteGeo(10), PocketServo.airborneLaunchDragSum(10), EPSILON, "D_a(10) = geo(10)");
        assertEquals(bruteS2(10), PocketServo.s2(10), EPSILON);
        assertEquals(42.514650, PocketServo.s2(10), 1.0e-2, "S2(10) ≈ 42.514650");
        // Ground locomotion: a_g·Σ(1−sq^k)/(1−sq), G=2, sprint attr → the §5 figure.
        double sprintAG = 0.98 * 0.13;
        assertEquals(sprintAG * bruteGroundSum(2, sqStone),
                PocketServo.groundLocomotion(2, sqStone, sprintAG), EPSILON);
        assertEquals(0.29, PocketServo.groundLocomotion(2, sqStone, sprintAG), 0.05, "G=2 sprint tail ≈ 0.29");
    }

    @Test
    void airTimeShiftsWithLaunchHeight() {
        // A grounded launch (height 0) matches the v1 sim; a launch from +0.2 stays
        // up longer. Hand pin: the 0.35716 signature stamp is a 10-tick flight.
        assertEquals(10, PocketServo.airTime(0.35716, 0.0), "signature standing stamp: 10-tick flight");
        assertTrue(PocketServo.airTime(0.30, 0.6) > PocketServo.airTime(0.30, 0.0),
                "a higher launch stays airborne longer");
        assertEquals(PocketServo.airTime(0.4), PocketServo.airTime(0.4, 0.0), "height 0 == the v1 sim");
    }

    @Test
    void dynamicChaseIsConsumedAndTakesPriorityOverTheMeasuredRate() {
        // Step 1 of the input-driven dynamic chase (spec 2026-07-07): when the reset
        // model is attached (withDynamicChase), the servo prices the chase via
        // DynamicChase over the REAL window and it OVERRIDES the flat chaseAlongAxis.
        // No facing (NaN yaw) → static target; attacker RTT −1 → no ping shift, so
        // wPrime = the config window (10). drift 0 → constant = d0 − chaseTravel.
        PredictorInputs base = new PredictorInputs(
                false, 0.6, 0.6, 0.2,           // airborne launch from +0.2 (a flight window exists)
                0.0, 0.28, 0.10,                // drift 0; a FLAT measured chase of 0.28 the dynamic must override
                -1, -1, Double.NaN, Double.NaN, // no ping shift, no pose
                Double.NaN, Double.NaN, 0,      // no facing → static target
                Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        PredictorInputs dynamic = base.withDynamicChase(0.28, 0, 0.5);
        // attackerNormalizedSpeed 0.10 keeps the chase-alignment floor (0.196·10 =
        // 1.964) below BOTH chases (dynamic 2.520, flat 2.800), so the floor stays off
        // and the pin isolates the dynamic-vs-flat priority it is here to test.
        FlightPrediction dyn = PocketServo.predict(SERVO, dynamic, 2.6, 0.0, 0.4, 0.4, 0.10);
        FlightPrediction flat = PocketServo.predict(SERVO, base, 2.6, 0.0, 0.4, 0.4, 0.10);
        assertEquals(10, dyn.windowTicks(), "no ping shift ⇒ the config window");
        // The dynamic branch prices the ramp, not base's flat 0.28·10.
        assertEquals(2.6 - DynamicChase.projectTravel(0.28, 0, 0.5, 10), dyn.constant(), EPSILON);
        assertEquals(2.6 - 2.5202734375, dyn.constant(), EPSILON); // hand value (phase-0 ramp deficit)
        assertEquals(2.6 - 0.28 * 10, flat.constant(), EPSILON);   // base falls to the flat measured chase
        assertTrue(dyn.constant() > flat.constant(),
                "the reset ramp closes less than the flat rate, so the victim is placed farther out");
    }

    /* ── the exact-inverse property across the full grid ───────────────────── */

    @Test
    void gridLandsExactlyOnTarget() {
        double[] verticalStamps = {0.25, 0.30, 0.35716, 0.40, 0.4607};
        double[] separations = {1.75, 2.00, 2.35, 2.60, 3.00};
        double[] residuals = {0.0, 0.02, 0.10, 0.25};
        double[] drifts = {-0.026, 0.0, 0.0196};
        double freshEra = 0.4;
        int checked = 0;
        for (boolean grounded : new boolean[] {true, false}) {
            double launchHeight = grounded ? 0.0 : 0.2;
            for (double stamp : verticalStamps) {
                for (double d0 : separations) {
                    for (double residual : residuals) {
                        for (double drift : drifts) {
                            // No facing (NaN yaw) → the boundary target degrades to the
                            // config static target, so the whole grid steers to 2.75.
                            PredictorInputs in = new PredictorInputs(
                                    grounded, 0.6, 0.6, launchHeight,
                                    drift, Double.NaN, PocketServo.WALK_BASELINE,
                                    -1, -1, Double.NaN, Double.NaN, Double.NaN, 0.0, 0);
                            PocketServo.Solution s = PocketServo.solve(
                                    SERVO, in, d0, residual, freshEra, stamp, 0.10);
                            assertFalse(s.declined(), "the core grid never declines");
                            assertEquals(SERVO.staticTarget(), s.target(), EPSILON,
                                    "no facing → the static target");
                            double landed = independentLanding(
                                    in, d0, residual, freshEra, s.sigmaStar(), stamp, 0.10, 10);
                            assertEquals(s.target(), landed, EPSILON,
                                    "σ* lands on target: grounded=" + grounded + " stamp=" + stamp
                                            + " d0=" + d0 + " R=" + residual + " drift=" + drift);
                            checked++;
                        }
                    }
                }
            }
        }
        assertEquals(600, checked, "the full 2×5×5×4×3 cross exercised");
    }

    /* ── the answer-denial-boundary geometry (§3.1/§3.2b; the 2.4.5 pin table) ─ */

    @Test
    void sepDenyIsTheVictimAnswerBoundary() {
        // w0=0.3, eyeV=1.62, PLAYER_HEIGHT=1.8 ⇒ dvertV = max(0, h − 0.18);
        // sepDeny = 0.3 + √(R_v² − dvertV²).
        assertEquals(3.290385, PocketServo.sepDeny(0.42, 3.0), 1.0e-6, "h 0.42, R_v 3.0");
        assertEquals(2.898942, PocketServo.sepDeny(0.42, 2.61), 1.0e-6, "h 0.42, handicap R_v 2.61");
        assertEquals(3.107247, PocketServo.sepDeny(1.238, 3.0), 1.0e-6, "h 1.238, R_v 3.0");
        assertEquals(2.685946, PocketServo.sepDeny(1.238, 2.61), 1.0e-6, "h 1.238, handicap R_v 2.61");
        assertEquals(2.170749, PocketServo.sepDeny(2.0, 2.61), 1.0e-6, "h 2.0, handicap R_v 2.61");
        // Eye above the head by ≥ R_v ⇒ the victim can never answer ⇒ no boundary.
        assertTrue(Double.isInfinite(PocketServo.sepDeny(5.0, 3.0)), "dvertV ≥ R_v collapses the boundary");
    }

    @Test
    void sepReachIsTheAttackerReachCap() {
        // Attacker eye 1.62; dvertA = max(0, h − 1.62, 1.62 − (h+1.8));
        // sepReach = 0.3 + √(R_a² − dvertA²).
        assertEquals(3.30, PocketServo.sepReach(0.42, 3.0), EPSILON, "h below the eye ⇒ dvertA 0");
        assertEquals(3.30, PocketServo.sepReach(1.238, 3.0), EPSILON, "h 1.238 still below the eye ⇒ dvertA 0");
        assertEquals(3.275836, PocketServo.sepReach(2.0, 3.0), 1.0e-6, "h 2.0 ⇒ dvertA 0.38");
    }

    @Test
    void boundaryTargetIsTheAnswerDenialSeparation() {
        // The FINAL 2.4.5 pin table: denyMargin 0.02, jitterMargin 0.15, floor 2.5,
        // R_a 3.0, eyeV 1.62. target = min(sepReach − jitter, max(floor, sepDeny + deny)).
        // low elevation, no handicap — the razor pocket: deny+0.02 (3.310385) exceeds
        // reach−0.15 (3.15), so reachability clamps and denial defers to the submodule.
        assertEquals(3.15, PocketServo.boundaryTarget(0.42, 3.0, 3.0, 0.02, 0.15, 2.5), 1.0e-6);
        // low elevation, handicap 0.87 — held just past the deny boundary, wide pocket.
        assertEquals(2.918942, PocketServo.boundaryTarget(0.42, 2.61, 3.0, 0.02, 0.15, 2.5), 1.0e-6);
        // mid elevation, no handicap — lift drops deny below reach even unhandicapped.
        assertEquals(3.127247, PocketServo.boundaryTarget(1.238, 3.0, 3.0, 0.02, 0.15, 2.5), 1.0e-6);
        // mid elevation, handicap 0.87.
        assertEquals(2.705946, PocketServo.boundaryTarget(1.238, 2.61, 3.0, 0.02, 0.15, 2.5), 1.0e-6);
        // high launch, handicap 0.87 — deny+0.02 (2.190749) is below the floor, so
        // max() clamps up to 2.5: the servo never pulls IN below the floor.
        assertEquals(2.5, PocketServo.boundaryTarget(2.0, 2.61, 3.0, 0.02, 0.15, 2.5), EPSILON);
    }

    @Test
    void emptyPocketFavoursReachability() {
        // When the deny boundary sits ABOVE the reach cap (a shallow launch at equal
        // reach) the min() picks reach − jitter: keep the victim hittable, defer denial
        // to the reach-handicap/vertical submodule.
        double sepDeny = PocketServo.sepDeny(0.42, 3.0);   // 3.290385
        double sepReach = PocketServo.sepReach(0.42, 3.0); // 3.30
        assertTrue(sepDeny < sepReach, "sanity: at h 0.42 with equal reach the deny sits just under reach");
        // deny+0.02 = 3.310385 > reach−0.15 = 3.15, so reachability wins.
        assertEquals(sepReach - 0.15, PocketServo.boundaryTarget(0.42, 3.0, 3.0, 0.02, 0.15, 2.5), 1.0e-9);
        // A collapsed deny boundary (victim can never answer) also clamps to reach − jitter.
        assertEquals(PocketServo.sepReach(5.0, 3.0) - 0.15,
                PocketServo.boundaryTarget(5.0, 3.0, 3.0, 0.02, 0.15, 2.5), 1.0e-9);
    }

    /* ── the boundary target driving the full solve ────────────────────────── */

    /** A BOUNDARY-mode config with the given effective victim reach (the handicap fold). */
    private static PocketServoConfig boundary(double victimReach) {
        return PocketServoConfig.of(2.85, 1.0, 0.8, 1.2, 10,
                TargetMode.BOUNDARY, victimReach, 3.0, 0.02, 0.15, 2.5);
    }

    @Test
    void boundaryTargetDrivesTheSolveWithFacing() {
        // A facing victim (yaw 0 → turn 0), no ping (t* = 0 → arc height = the grounded
        // launch's 0), handicap-effective reach 2.61: the solve reads the geometry at
        // the arc height at t* and steers there, and σ* lands there through the fold.
        PredictorInputs facing = new PredictorInputs(
                true, 0.6, 0.6, 0.0, 0.0, Double.NaN, 0.10,
                -1, -1, Double.NaN, Double.NaN, /*yaw*/0.0, Double.NaN, 0);
        PocketServo.Solution s = PocketServo.solve(boundary(2.61), facing, 2.35, 0.0, 0.4, 0.35716, 0.10);
        assertFalse(s.declined());
        assertEquals(0.0, s.arcHeightTStar(), EPSILON, "t* = 0 (facing, no ping) → the launch height");
        double expected = PocketServo.boundaryTarget(
                s.arcHeightTStar(), 2.61, 3.0, 0.02, 0.15, 2.5);
        assertEquals(expected, s.target(), EPSILON, "the solve steers to the geometric boundary target");
        double landed = independentLanding(facing, 2.35, 0.0, 0.4, s.sigmaStar(), 0.35716, 0.10, 10);
        assertEquals(s.target(), landed, EPSILON, "σ* lands exactly on the boundary target");
    }

    @Test
    void handicapOpensTheKeepablePocket() {
        // The composition invariant: at the same geometry a SHORTER effective victim
        // reach lowers the deny boundary, so the boundary target moves IN (a keepable
        // pocket the attacker still reaches). This is why the reach nerf keeps a combo.
        PredictorInputs facing = new PredictorInputs(
                true, 0.6, 0.6, 0.0, 0.0, Double.NaN, 0.10,
                -1, -1, Double.NaN, Double.NaN, /*yaw*/0.0, Double.NaN, 0);
        double noHandicap = PocketServo.solve(boundary(3.0), facing, 2.35, 0.0, 0.4, 0.35716, 0.10).target();
        double handicapped = PocketServo.solve(boundary(2.61), facing, 2.35, 0.0, 0.4, 0.35716, 0.10).target();
        assertTrue(handicapped < noHandicap,
                "a shortened victim reach lowers the target (" + handicapped + " < " + noHandicap + ")");
    }

    @Test
    void boundaryFallsBackToStaticTargetWithoutFacing() {
        // No facing (NaN yaw) → the geometry is unmeasurable → the STATIC fallback.
        PredictorInputs noYaw = new PredictorInputs(
                true, 0.6, 0.6, 0.0, 0.0, 0.2806, 0.10,
                -1, -1, Double.NaN, Double.NaN, Double.NaN, 0.0, 0);
        PocketServo.Solution s = PocketServo.solve(boundary(2.61), noYaw, 2.35, 0.0, 0.4, 0.35716, 0.10);
        assertEquals(2.85, s.target(), 0.0, "NaN yaw → the config static target (2.85)");
    }

    @Test
    void staticModePinsTheTargetRegardlessOfFacing() {
        // STATIC mode ignores the geometry even WITH facing: the fixed separation stands.
        PocketServoConfig staticCfg = PocketServoConfig.of(2.70, 1.0, 0.8, 1.2, 10,
                TargetMode.STATIC, 2.61, 3.0, 0.02, 0.15, 2.5);
        PredictorInputs facing = new PredictorInputs(
                true, 0.6, 0.6, 0.0, 0.0, Double.NaN, 0.10,
                -1, -1, Double.NaN, Double.NaN, /*yaw*/0.0, Double.NaN, 0);
        PocketServo.Solution s = PocketServo.solve(staticCfg, facing, 2.35, 0.0, 0.4, 0.35716, 0.10);
        assertEquals(2.70, s.target(), 0.0, "STATIC mode holds the fixed target");
    }

    /* ── the touchdown-aware launch branch (servo-lab 2.4.5) ───────────────── */

    /** The shipped static fallback (2.85) — the lab pins below have NaN facing, so it is the target. */
    private static final PocketServoConfig SHIPPED_ANCHOR = PocketServoConfig.of(2.85, 1.0, 0.8, 1.2, 10);

    @Test
    void touchdownBoundaryHitRepricesAsAGroundedLaunch() {
        // The era ordering captures the END of the previous tick, so a descending
        // boundary hit reads airborne (+0.30, vy −0.35) — but the client moves by
        // vy BEFORE the stamp applies: 0.30 − 0.35 ≤ 0 grounds it, so the flight
        // must be priced as a grounded ground-level launch.
        PocketServo.Solution s = PocketServo.solve(
                SHIPPED_ANCHOR, touchdown(0.30, -0.35, 0.116), 2.55, 0.02, 0.325, 0.35716, 0.10);
        assertTrue(s.launchRepriced(), "the descending boundary capture reprices");
        assertTrue(s.launchGrounded(), "the effective launch state is grounded");
        assertEquals(10, s.airTicks(),
                "repriced airTime is the ground-level fold (0.35716 → 10t), not the +0.30 launch's 11t");
        assertEquals(PocketServo.groundedLaunchDragSum(10, 0.6 * Q), s.dragSum(), EPSILON,
                "the drag schedule takes the grounded launch branch: D_g(10) = 1 + 0.546·geo(9)");
        // Hand pin WITH the 2.4.6 chase-alignment floor: the measured 0.116 b/t
        // (1.16 blocks) is under the aligned-sprint floor (0.7·0.2806·1.0·10 =
        // 1.9642), so the chase floors to 1.9642 — an active-combo attacker is assumed
        // to close at 0.7× sprint, not the window-averaged 0.116 that under-reads the
        // post-knock burst. d0 2.55, R 0.02, F 0.325 (plain stance), static target
        // 2.85 (NaN facing), w' 10, grounded dragSum 4.470559213.
        //   σ* = (2.85 − (2.55 − 1.9642) − 4.470559213·0.02) / (0.325·4.470559213)
        //      = 2.174788816 / 1.452931744 = 1.496827931 → clamps to 1.2.
        assertEquals(1.496827931, s.sigmaStar(), 1.0e-9);
        assertEquals(1.2, s.sigma(), EPSILON, "the floored chase lifts σ* past the max clamp");
        // And the repriced σ* lands exactly on target through the independent fold.
        double landed = independentLanding(
                touchdown(0.30, -0.35, 0.116).asGroundedLaunch(),
                2.55, 0.02, 0.325, s.sigmaStar(), 0.35716, 0.10, 10);
        assertEquals(s.target(), landed, EPSILON);
    }

    @Test
    void theChaseFloorPreventsTheAirborneFalseLowMode() {
        // The SAME hit priced with no boundary vy (the airborne branch): D_a(10) =
        // geo(10) = 6.784265354, a 52% overtravel per unit shipped speed. Pre-2.4.6
        // this collapsed σ* into the lab's false-low min-clamp mode (σ* 0.60 → pinned
        // 0.8 — the undershoot). The chase-alignment floor now lifts the under-read
        // 0.116 b/t measured chase to 0.7·0.2806·1.0·10 = 1.9642, so σ* holds INTERIOR
        // (≈ 1) instead of collapsing:
        //   σ* = (2.85 − (2.55 − 1.9642) − 6.784265354·0.02) / (0.325·6.784265354)
        //      = 2.128815307 / 2.204886240 = 0.965362591 — no false-low.
        PredictorInputs blind = new PredictorInputs(
                false, 0.6, 0.6, 0.30, 0.0, 0.116, 0.10,
                -1, -1, Double.NaN, Double.NaN, Double.NaN, 0.0, 0);
        PocketServo.Solution s = PocketServo.solve(SHIPPED_ANCHOR, blind, 2.55, 0.02, 0.325, 0.35716, 0.10);
        assertFalse(s.launchRepriced(), "no boundary vy — the branch cannot fire (byte-identical pre-round)");
        assertEquals(PocketServo.airborneLaunchDragSum(10), s.dragSum(), EPSILON);
        assertEquals(0.965362591, s.sigmaStar(), 1.0e-9);
        assertEquals(s.sigmaStar(), s.sigma(), EPSILON,
                "the chase floor holds σ* interior — the false-low min-clamp no longer binds");
        // The lab's own travel validation at the As plain-stance stamp |H| = 0.26:
        // grounded 0.26·D_g(10) = 1.1623 (measured 1.162 — exact); the airborne
        // mispricing models 0.26·D_a(10) = 1.7639 (the lab's 1.764 model row).
        assertEquals(1.162, 0.26 * PocketServo.groundedLaunchDragSum(10, 0.6 * Q), 1.0e-3,
                "grounded model == the lab's measured flight");
        assertEquals(1.764, 0.26 * PocketServo.airborneLaunchDragSum(10), 1.0e-3,
                "the airborne mispricing == the lab's overestimated model row");
    }

    @Test
    void repriceFiresOnlyBelowOneRemainingFallTick() {
        // One more airborne tick to go (0.50 − 0.35 > 0): no reprice.
        assertFalse(PocketServo.solve(SHIPPED_ANCHOR, touchdown(0.50, -0.35, Double.NaN),
                2.55, 0.02, 0.325, 0.35716, 0.10).launchRepriced());
        // Exactly one fall tick (0.35 − 0.35 == 0): the client grounds — reprice.
        assertTrue(PocketServo.solve(SHIPPED_ANCHOR, touchdown(0.35, -0.35, Double.NaN),
                2.55, 0.02, 0.325, 0.35716, 0.10).launchRepriced());
        // Rising: never (the arc is still ascending; touchdown is not next tick).
        assertFalse(PocketServo.solve(SHIPPED_ANCHOR, touchdown(0.10, 0.20, Double.NaN),
                2.55, 0.02, 0.325, 0.35716, 0.10).launchRepriced());
        // Already grounded at capture: the grounded branch is the capture's own —
        // nothing was repriced.
        PredictorInputs grounded = new PredictorInputs(
                true, 0.6, 0.6, 0.0, 0.0, Double.NaN, 0.10,
                -1, -1, Double.NaN, Double.NaN, Double.NaN, 0.0, 0,
                Double.NaN, Double.NaN, -0.0784);
        assertFalse(PocketServo.solve(SHIPPED_ANCHOR, grounded,
                2.55, 0.02, 0.325, 0.35716, 0.10).launchRepriced());
    }

    /** An airborne descending boundary capture: height above ground, boundary vy, measured chase. */
    private static PredictorInputs touchdown(double launchHeight, double vy, double chase) {
        return new PredictorInputs(
                false, 0.6, 0.6, launchHeight, 0.0, chase, 0.10,
                -1, -1, Double.NaN, Double.NaN, Double.NaN, 0.0, 0,
                Double.NaN, Double.NaN, vy);
    }

    @Test
    void groundedLaunchTravelsLessThanAirborne() {
        // The mandatory launch-state branch: a grounded launch takes one ground-drag
        // decay on the launch tick, so its drag schedule is strictly SMALLER than the
        // pure-air airborne sum — the ~1.9-block overshoot the branch fixes (§1.2).
        PredictorInputs grounded = degradedGround(true, 0.0);
        PredictorInputs air = degradedGround(false, 0.2);
        PocketServo.Solution g = PocketServo.solve(SERVO, grounded, 2.35, 0.0, 0.4, 0.35716, 0.10);
        PocketServo.Solution a = PocketServo.solve(SERVO, air, 2.35, 0.0, 0.4, 0.35716, 0.10);
        assertTrue(g.dragSum() < a.dragSum(), "grounded D_g < airborne D_a");
    }

    @Test
    void windowOutlivesFlightFoldsAGroundTail() {
        // V0 0.25 lands at tick 8; with t* = 10 there are G = 2 grounded ticks inside
        // the window (the §5 / §7.1 ground-tail case).
        PredictorInputs in = new PredictorInputs(
                true, 0.6, 0.6, 0.0, 0.0196, Double.NaN, 0.13,
                -1, -1, Double.NaN, Double.NaN, Double.NaN, 0.0, 0);
        PocketServo.Solution s = PocketServo.solve(SERVO, in, 2.35, 0.0, 0.4, 0.25, 0.10);
        assertEquals(8, s.airTicks());
        assertEquals(10, s.windowTicks());
        assertEquals(8, s.landTick(), "no victim ping → touchdown at airTime");
        // The independent fold (which includes the G=2 sprint tail) still lands on target.
        double landed = independentLanding(in, 2.35, 0.0, 0.4, s.sigmaStar(), 0.25, 0.10, 10);
        assertEquals(s.target(), landed, EPSILON);
    }

    /* ── the gap-aware window + ground return (servo-lab 2.4.5) ────────────── */

    @Test
    void gapAwareWindowFollowsTheMeasuredCadence() {
        // The measured cadence EMA replaces the fixed config cadence as the horizon.
        assertEquals(18, cadenced(18.0, -1).windowTicks(), "cadence 18 → an 18-tick window");
        assertEquals(18, cadenced(18.0, -1).horizonTicks());
        assertEquals(17, cadenced(18.0, 100).windowTicks(), "rttA 100 still shaves the horizon: 18 − 1");
        assertEquals(15, cadenced(15.4, -1).windowTicks(), "round-to-nearest, the t* rounding rule");
        // Unmeasured / degenerate cadences keep the config windowTicks — byte-identical.
        assertEquals(10, cadenced(Double.NaN, -1).windowTicks(), "no cadence → the config 10");
        assertEquals(10, cadenced(0.5, -1).windowTicks(), "sub-tick cadence is no cadence");
        // The robustness ceiling (3× the default max-gap): a garbage EMA can never
        // size a giant schedule.
        assertEquals(60, cadenced(500.0, -1).windowTicks(), "cadence ceiling 60");
    }

    @Test
    void gapWindowPricesTheGroundReturnTail() {
        // The lab's slow-cadence returning-victim cell (the Cr shape), hand-derived
        // end to end. Grounded signature launch V0 0.35716 (airTime 10, tLand 10),
        // cadence 18 → w' 18 → G = 8 post-touchdown ticks inside the window; the
        // victim holds toward the attacker (â = −0.0196) at sprint attribute 0.13;
        // measured window chase 0.0972 b/t (the lab's 1.75 blocks / 18t); d0 2.5,
        // R 0.02, F 0.325, static target 2.85 (NaN facing).
        //   drag schedule: Π grounded launch, air 2..10, ground tail 11..18
        //                  → D(18) = 4.981141159
        //   drift  = −0.0196 · S2(min(18, 10)) = −0.0196 · 42.514650307 = −0.833287146
        //   tail   = −(0.98·0.13) · Σ_{k=1..8}(1 − 0.546^k)/0.454      = −1.910117693
        //   chase  = floor 0.7·0.2806·1.0·18 = 3.53556  (the measured 0.0972·18 =
        //            1.7496 is under the aligned-sprint floor at attr 0.10 → floored)
        //   constant = 2.5 − 3.53556 − 0.833287146 − 1.910117693         = −3.778964839
        //   σ* = (2.85 + 3.778964839 − 4.981141159·0.02) / (0.325·4.981141159)
        //      = 6.529342016 / 1.618870877 = 4.033269182
        // → clamps to 1.2: a sprint-returning victim at 17–19t cadence is honestly
        // clamp-starved (the lab's σ-needed med 2.82 for that cell), and the servo
        // holds the boundary instead of shipping a false-low knock.
        PredictorInputs in = new PredictorInputs(
                true, 0.6, 0.6, 0.0, -0.0196, 0.0972, 0.13,
                -1, -1, Double.NaN, Double.NaN, Double.NaN, 0.0, 0,
                Double.NaN, Double.NaN, Double.NaN, 18.0);
        PocketServo.Solution s = PocketServo.solve(SHIPPED_ANCHOR, in, 2.5, 0.02, 0.325, 0.35716, 0.10);
        assertEquals(18, s.windowTicks());
        assertEquals(10, s.landTick(), "touchdown inside the window — the tail is live");
        assertEquals(4.981141159, s.dragSum(), 1.0e-9, "Π folds the knock's own ground tail after tLand");
        assertEquals(-0.833287146 - 1.910117693, s.driftTravel(), 1.0e-9,
                "air drift truncated at touchdown + the §5 attribute-rate ground return");
        assertEquals(4.033269182, s.sigmaStar(), 1.0e-9);
        assertEquals(1.2, s.sigma(), EPSILON, "clamp-starved — the honesty boundary holds");
        // The unclamped σ* still lands exactly on target through the independent fold
        // (the exact-inverse property survives the gap-aware window).
        double landed = independentLanding(in, 2.5, 0.02, 0.325, s.sigmaStar(), 0.35716, 0.10, 18);
        assertEquals(s.target(), landed, EPSILON);
    }

    /** A grounded-launch solve carrying a measured cadence EMA (and optionally attacker RTT). */
    private static PocketServo.Solution cadenced(double cadenceEma, int rttA) {
        PredictorInputs in = new PredictorInputs(
                true, 0.6, 0.6, 0.0, 0.0, Double.NaN, 0.10,
                rttA, -1, Double.NaN, Double.NaN, Double.NaN, 0.0, 0,
                Double.NaN, Double.NaN, Double.NaN, cadenceEma);
        // A tall stamp keeps every window here inside the flight (no tail term).
        return PocketServo.solve(SHIPPED_ANCHOR, in, 2.35, 0.0, 0.4, 1.2, 0.10);
    }

    /* ── ping horizons (§4) ────────────────────────────────────────────────── */

    @Test
    void pingShiftsHorizonAndArc() {
        // Attacker half-RTT shaves t* (round-to-nearest); victim half-RTT shifts the
        // arc's touchdown late (floor).
        assertEquals(9, horizonFor(100, -1).horizonTicks(), "rttA 100 → shiftA 1 → t* 9");
        assertEquals(7, horizonFor(250, -1).horizonTicks(), "rttA 250 → shiftA round(2.5)=3 → t* 7");
        assertEquals(10, horizonFor(0, -1).horizonTicks(), "rttA 0 → t* = cadence");
        PocketServo.Solution v100 = horizonFor(-1, 100);
        assertEquals(1, v100.shiftVictimTicks(), "rttV 100 → shiftV 1");
        assertEquals(1 + v100.airTicks(), v100.landTick(), "victim ping delays touchdown by shiftV");
        assertEquals(2, horizonFor(-1, 250).shiftVictimTicks(), "rttV 250 → shiftV floor(2.5)=2");
    }

    /* ── ice decline + degenerate guards ──────────────────────────────────── */

    @Test
    void iceLandingDeclinesTheServo() {
        // Predicted landing slip > 0.7 (packed ice 0.98): the pocket geometry itself
        // changes — decline entirely (σ = 1), never force a non-era knock (§6 issue 6).
        PredictorInputs ice = new PredictorInputs(
                true, 0.98, 0.98, 0.0, 0.0, Double.NaN, 0.10,
                -1, -1, Double.NaN, Double.NaN, Double.NaN, 0.0, 0);
        PocketServo.Solution s = PocketServo.solve(SERVO, ice, 2.35, 0.0, 0.4, 0.35716, 0.10);
        assertTrue(s.declined());
        assertEquals(1.0, s.sigma(), 0.0);
        // The geometry is not evaluated on a declined solve (the 0 sentinel = N/A);
        // the static target is reported for reference.
        assertEquals(0.0, s.sepDeny(), 0.0);
        assertEquals(0.0, s.sepReach(), 0.0);
        assertEquals(SERVO.staticTarget(), s.target(), 0.0);
    }

    @Test
    void degenerateFlightsDecline() {
        PredictorInputs base = degradedGround(true, 0.0);
        // Air-time below 3 ticks: no meaningful flight to shape.
        assertTrue(PocketServo.solve(SERVO, base, 2.35, 0.0, 0.4, 0.01, 0.10).declined(),
                "tiny vertical stamp → airTime < 3 → decline");
        // A collapsed horizon (a huge attacker ping shaves t* below 1).
        PredictorInputs hugePing = new PredictorInputs(
                true, 0.6, 0.6, 0.0, 0.0, Double.NaN, 0.10,
                1000, -1, Double.NaN, Double.NaN, Double.NaN, 0.0, 0);
        assertTrue(PocketServo.solve(SERVO, hugePing, 2.35, 0.0, 0.4, 0.35716, 0.10).declined(),
                "t* < 1 → decline");
    }

    @Test
    void inactiveAndNoLeverReturnOne() {
        PredictorInputs in = degradedGround(true, 0.0);
        assertEquals(1.0, PocketServo.sigma(
                PocketServoConfig.INACTIVE, in, 2.35, 0.1, 0.4, 0.35716, 0.10), 0.0);
        assertEquals(1.0, PocketServo.sigma(SERVO, in, 2.35, 0.1, 0.0, 0.35716, 0.10), 0.0,
                "no fresh horizontal — no lever");
    }

    /* ── the retaliation-budget geometry that sizes t* (§3.1/§4) ───────────── */

    @Test
    void turnIsContinuousAndFlickAware() {
        assertEquals(0.0, PocketServo.turn(30.0, Double.NaN), EPSILON, "facing (≤60°) → 0");
        assertEquals(0.0, PocketServo.turn(60.0, Double.NaN), EPSILON, "exactly 60° → 0");
        assertEquals(1.0, PocketServo.turn(90.0, Double.NaN), EPSILON, "90°, unknown rate → (30)/30 = 1.0");
        assertEquals(3.0, PocketServo.turn(150.0, 10.0), EPSILON, "150°, slow 10°/t → (90)/max(10,30) = 3.0");
        assertEquals(1.0, PocketServo.turn(150.0, 90.0), EPSILON, "150°, fast 90°/t flick → (90)/90 = 1.0");
        assertEquals(0.0, PocketServo.turn(Double.NaN, 90.0), EPSILON, "NaN facing → 0 (no geometry)");
    }

    @Test
    void tAllowIsContinuousPingPlusTurn() {
        // tPing = rttV/100 (real ticks, no floor) + turn. The journal examples' budgets.
        assertEquals(3.8, PocketServo.tAllow(80, 150.0, 10.0), EPSILON,
                "faced-away high-ping: 80/100 + 90/30 = 0.8 + 3.0");
        assertEquals(1.4, PocketServo.tAllow(40, 90.0, Double.NaN), EPSILON,
                "perpendicular mid-ping: 40/100 + 30/30 = 0.4 + 1.0");
        assertEquals(0.1, PocketServo.tAllow(10, 30.0, Double.NaN), EPSILON,
                "facing low-ping: 10/100 + 0 = 0.1");
        assertEquals(1.0, PocketServo.tAllow(-1, 90.0, Double.NaN), EPSILON,
                "unavailable RTT (<0) → no ping slack, just the turn");
    }

    @Test
    void windowChaseRateMeasuresThePostHitWindow() {
        // The servo-lab 2.4.5 chase correction: the attacker's displacement over the
        // just-completed inter-hit gap, axis-projected, per tick.
        assertEquals(0.097, PocketServo.windowChaseRate(10.0, 4.0, 11.746, 4.0, 1.0, 0.0, 18), EPSILON,
                "closing displacement / gap, on-axis");
        // Moving AWAY from the victim (against the axis) reads negative — the w-tap
        // back-off phase the old pre-hit trend mistook for the whole window.
        assertEquals(-0.10, PocketServo.windowChaseRate(10.0, 4.0, 9.0, 4.0, 1.0, 0.0, 10), EPSILON);
        // Off-axis displacement projects: (0, 1.8) on axis (0.6, 0.8) over 12 → 1.44/12.
        assertEquals(0.12, PocketServo.windowChaseRate(0.0, 0.0, 0.0, 1.8, 0.6, 0.8, 12), EPSILON);
        // A degenerate gap is no window at all.
        assertTrue(Double.isNaN(PocketServo.windowChaseRate(0.0, 0.0, 1.0, 0.0, 1.0, 0.0, 0)));
        // The per-combo EMA rides the seeded blend: a NaN prior seeds to the first
        // window; 0.3·0.15 + 0.7·0.097 = 0.1129 thereafter.
        assertEquals(0.097, PocketServo.chaseEma(Double.NaN, 0.097), EPSILON, "first window seeds");
        assertEquals(0.1129, PocketServo.chaseEma(0.097, 0.15), EPSILON, "0.3·0.15 + 0.7·0.097");
    }

    @Test
    void chaseEmaSeedsThenBlends() {
        // NaN prior seeds to the instantaneous rate; then α=0.3 blend.
        assertEquals(0.30, PocketServo.chaseEma(Double.NaN, 0.30), EPSILON, "first hit seeds");
        double blended = PocketServo.CHASE_EMA_ALPHA * 0.50 + (1.0 - PocketServo.CHASE_EMA_ALPHA) * 0.30;
        assertEquals(blended, PocketServo.chaseEma(0.30, 0.50), EPSILON, "0.3·0.5 + 0.7·0.3 = 0.36");
        assertEquals(0.36, blended, 1.0e-9);
    }

    /* ── independent fold + brute references (never the production sum code) ─── */

    private static double independentLanding(
            PredictorInputs in, double d0, double r, double f, double sigma,
            double vstamp, double attrA, int cadence) {
        int shiftA = in.attackerRttMillis() >= 0
                ? (int) Math.round((in.attackerRttMillis() / 2.0) / 50.0) : 0;
        int shiftV = in.victimRttMillis() >= 0
                ? (int) Math.floor((in.victimRttMillis() / 2.0) / 50.0) : 0;
        int wPrime = cadence - shiftA;
        int airTime = localAirTime(vstamp, in.launchHeight());
        int tLand = shiftV + airTime;
        double sqLaunch = in.launchSlip() * Q;
        double sqLand = in.landingSlip() * Q;

        // Knock horizontal: Σ shipped·Π with the launch-state drag branch + ground tail.
        double shipped = r + sigma * f;
        double pi = 1.0;
        double knockTravel = 0.0;
        for (int k = 1; k <= wPrime; k++) {
            knockTravel += shipped * pi;
            boolean grounded = (k == 1) ? in.launchGrounded() : (k > tLand);
            double drag = grounded ? (k == 1 ? sqLaunch : sqLand) : Q;
            pi *= drag;
        }
        // Air drift over the AIRBORNE ticks (S2 truncates at touchdown — grounded
        // ticks use the ground constants INSTEAD, §2.5) + the ground-tail
        // locomotion over the G grounded ticks.
        double driftTravel = in.driftAlongAxis() * bruteS2(Math.min(wPrime, tLand));
        int g = Math.max(0, wPrime - shiftV - airTime);
        double groundAttr = in.victimNormalizedSpeed() > 0.0 ? in.victimNormalizedSpeed() : 0.10;
        double tail = (g > 0 && in.driftAlongAxis() != 0.0)
                ? Math.signum(in.driftAlongAxis()) * (0.98 * groundAttr) * bruteGroundSum(g, sqLand)
                : 0.0;
        // Chase — mirror the solve's measured/attribute rate AND the 2.4.6
        // chase-alignment floor (the independent fold verifies the affine inversion,
        // so it must model the same floored chase the solve prices).
        double chaseRate = !Double.isNaN(in.chaseAlongAxis())
                ? in.chaseAlongAxis()
                : 0.2806 * ((attrA > 0 ? attrA : 0.10) / 0.10);
        double chaseTravel = Math.max(chaseRate * wPrime,
                PocketServo.CHASE_ALIGNMENT * PocketServo.SPRINT_GROUND_SPEED
                        * ((attrA > 0 ? attrA : 0.10) / 0.10) * wPrime);
        return d0 + knockTravel + driftTravel + tail - chaseTravel;
    }

    private static int localAirTime(double vstamp, double launchHeight) {
        double y = launchHeight;
        double vy = vstamp;
        if (y <= 0.0 && !(vy > 0.0)) {
            return 0;
        }
        int ticks = 0;
        while (ticks < 5000) {
            y += vy;
            ticks++;
            if (y <= 0.0) {
                break;
            }
            vy = (vy - Decay.DEFAULT_GRAVITY) * Decay.VERTICAL_DRAG;
            if (vy < -Decay.TERMINAL_VELOCITY) {
                vy = -Decay.TERMINAL_VELOCITY;
            }
        }
        return ticks;
    }

    private static double bruteGeo(int n) {
        double sum = 0.0;
        double term = 1.0;
        for (int k = 0; k < n; k++) {
            sum += term;
            term *= Q;
        }
        return sum;
    }

    private static double bruteS2(int n) {
        double sum = 0.0;
        for (int k = 1; k <= n; k++) {
            sum += bruteGeo(k);
        }
        return sum;
    }

    private static double bruteGroundSum(int g, double sq) {
        double sum = 0.0;
        double sqPow = 1.0;
        for (int k = 1; k <= g; k++) {
            sqPow *= sq;
            sum += (1.0 - sqPow) / (1.0 - sq);
        }
        return sum;
    }

    private static PredictorInputs degradedGround(boolean grounded, double launchHeight) {
        return new PredictorInputs(
                grounded, 0.6, 0.6, launchHeight, 0.0, Double.NaN, 0.10,
                -1, -1, Double.NaN, Double.NaN, Double.NaN, 0.0, 0);
    }

    private static PocketServo.Solution horizonFor(int rttA, int rttV) {
        PredictorInputs in = new PredictorInputs(
                true, 0.6, 0.6, 0.0, 0.0, Double.NaN, 0.10,
                rttA, rttV, Double.NaN, Double.NaN, Double.NaN, 0.0, 0);
        return PocketServo.solve(SERVO, in, 2.35, 0.0, 0.4, 0.4607, 0.10);
    }
}
