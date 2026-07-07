package me.vexmc.mental.kernel.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins for the precision-round pocket servo (combo-hold §3.2b;
 * precision-derivation §7.1). The load-bearing test is {@link #gridLandsExactlyOnTarget()}:
 * across the grounded/air launch branches, vertical stamps, separations, residuals,
 * drift and ping cells the derivation names, the raw σ* the solve returns — fed
 * through an INDEPENDENT tick-by-tick era flight fold (never the production sum
 * code) — lands the victim within 1e-9 of the (possibly dynamic) target. The
 * closed forms (D_g / D_a / S2 / ground locomotion), the ping-shift arithmetic, the
 * ice decline, the degenerate guards and the dynamic-target function are hand-pinned
 * separately so the fold can never drift from the physics it inverts.
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
                            PredictorInputs in = new PredictorInputs(
                                    grounded, 0.6, 0.6, launchHeight,
                                    drift, Double.NaN, PocketServo.WALK_BASELINE,
                                    -1, -1, Double.NaN, Double.NaN, Double.NaN, 0.0, 0);
                            PocketServo.Solution s = PocketServo.solve(
                                    SERVO, in, d0, residual, freshEra, stamp, 0.10);
                            assertFalse(s.declined(), "the core grid never declines");
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

    /* ── the touchdown-aware launch branch (servo-lab 2.4.5) ───────────────── */

    /** The SHIPPED anchor (2.85, the target-v2 retune) — the lab pins below derive against it. */
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
        // Hand pin, the lab's counterfactual: d0 2.55, R 0.02, F 0.325 (plain
        // stance), measured chase 0.116 b/t, target 2.85, w' 10.
        //   σ* = (2.85 − (2.55 − 1.16) − 4.470559213·0.02) / (0.325·4.470559213)
        //      = 1.370588824 / 1.452931744 = 0.943326362 — interior, ≈ 1 (the σ=1
        //        counterfactual the lab measured settling in the pocket).
        assertEquals(0.943326362, s.sigmaStar(), 1.0e-9);
        assertEquals(s.sigmaStar(), s.sigma(), EPSILON, "interior: the clamp does not bind");
        // And the repriced σ* lands exactly on target through the independent fold.
        double landed = independentLanding(
                touchdown(0.30, -0.35, 0.116).asGroundedLaunch(),
                2.55, 0.02, 0.325, s.sigmaStar(), 0.35716, 0.10, 10);
        assertEquals(s.target(), landed, EPSILON);
    }

    @Test
    void withoutTheRepriceTheAirborneModelManufacturesTheFalseLowMode() {
        // The SAME hit priced with no boundary vy (the pre-round arity): the solve
        // takes the airborne branch — D_a(10) = geo(10) = 6.784265354, a 52%
        // overtravel per unit shipped speed — and σ* collapses into the lab's
        // false-low min-clamp mode:
        //   σ* = (2.85 − 1.39 − 6.784265354·0.02) / (0.325·6.784265354)
        //      = 1.324314693 / 2.204886240 = 0.600627220 → pinned at 0.8.
        PredictorInputs blind = new PredictorInputs(
                false, 0.6, 0.6, 0.30, 0.0, 0.116, 0.10,
                -1, -1, Double.NaN, Double.NaN, Double.NaN, 0.0, 0);
        PocketServo.Solution s = PocketServo.solve(SHIPPED_ANCHOR, blind, 2.55, 0.02, 0.325, 0.35716, 0.10);
        assertFalse(s.launchRepriced(), "no boundary vy — the branch cannot fire (byte-identical pre-round)");
        assertEquals(PocketServo.airborneLaunchDragSum(10), s.dragSum(), EPSILON);
        assertEquals(0.600627220, s.sigmaStar(), 1.0e-9);
        assertEquals(0.8, s.sigma(), EPSILON, "the false-low mode pins the min clamp");
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
        // R 0.02, F 0.325, anchor 2.85.
        //   drag schedule: Π grounded launch, air 2..10, ground tail 11..18
        //                  → D(18) = 4.981141159
        //   drift  = −0.0196 · S2(min(18, 10)) = −0.0196 · 42.514650307 = −0.833287146
        //   tail   = −(0.98·0.13) · Σ_{k=1..8}(1 − 0.546^k)/0.454      = −1.910117693
        //   chase  = 0.0972 · 18                                        = 1.7496
        //   constant = 2.5 − 1.7496 − 0.833287146 − 1.910117693         = −1.993004839
        //   σ* = (2.85 + 1.993004839 − 4.981141159·0.02) / (0.325·4.981141159)
        //      = 4.743382016 / 1.618870877 = 2.930055809
        // → clamps to 1.2: a sprint-returning victim at 17–19t cadence is honestly
        // clamp-starved (the lab's σ-needed med 2.82 for that cell), and the servo
        // holds the boundary instead of shipping the false-low 0.8.
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
        assertEquals(2.930055809, s.sigmaStar(), 1.0e-9);
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

    /* ── the V2 dynamic target (§3.3; target-v2 repairs #1–#4) ─────────────── */

    /** A wide-interval synthetic config so the exposure min-selection is exercisable
     *  (at signature-knock scale capEff collapses to the anchor — see below). */
    private static final PocketServoConfig WIDE =
            PocketServoConfig.of(2.30, 1.0, 0.8, 1.2, 10, TargetMode.DYNAMIC, 3.20);

    @Test
    void turnIsContinuousAndFlickAware() {
        // repair #4: 0 within 60° of the axis; else (|yaw|−60)/max(rate, 30°/t).
        assertEquals(0.0, PocketServo.turn(30.0, Double.NaN), EPSILON, "facing (≤60°) → 0");
        assertEquals(0.0, PocketServo.turn(60.0, Double.NaN), EPSILON, "exactly 60° → 0");
        assertEquals(1.0, PocketServo.turn(90.0, Double.NaN), EPSILON, "90°, unknown rate → (30)/30 = 1.0");
        assertEquals(3.0, PocketServo.turn(150.0, 10.0), EPSILON, "150°, slow 10°/t → (90)/max(10,30) = 3.0");
        assertEquals(1.0, PocketServo.turn(150.0, 90.0), EPSILON, "150°, fast 90°/t flick → (90)/90 = 1.0");
        assertEquals(0.0, PocketServo.turn(Double.NaN, 90.0), EPSILON, "NaN facing → 0 (no geometry)");
    }

    @Test
    void tAllowIsContinuousPingPlusTurn() {
        // repair #4: tPing = rttV/100 (real ticks, no floor) + turn. The three journal
        // examples' budgets, recomputed exactly from the constants.
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
    void capEffIsChaseAwareAndClampedAboveAnchor() {
        // repair #3: capEff = hitEdge(3.0) − 0.5·chaseEma − landingSlack(0.17), clamped
        // to [anchor, hitCap]. A wide-anchor config exposes the raw value; the shipped
        // signature anchor clamps it up (the verifier's hole-12 inverted interval).
        double raw = PocketServo.HIT_EDGE - 0.5 * 0.12 - PocketServo.LANDING_SLACK; // 2.77
        assertEquals(2.77, raw, 1.0e-9);
        assertEquals(2.77, PocketServo.capEff(WIDE, 0.12), EPSILON, "raw value inside [2.30, 3.20]");
        // Signature chase 0.2806 → raw 2.6897 < the shipped 2.85 anchor → clamps to it.
        PocketServoConfig shipped = PocketServoConfig.of(
                2.85, 1.0, 0.8, 1.2, 10, TargetMode.DYNAMIC, 2.95);
        assertEquals(2.85, PocketServo.capEff(shipped, 0.2806), EPSILON,
                "capEff clamps up to the anchor rather than inverting the interval");
    }

    @Test
    void windowChaseRateMeasuresThePostHitWindow() {
        // The servo-lab 2.4.5 chase correction: the attacker's displacement over the
        // just-completed inter-hit gap, axis-projected, per tick. Lab scale: the
        // slow-cadence cell measured ~1.75 blocks of actual re-chase over an 18-tick
        // window → 1.746 / 18 = 0.097 b/t exactly.
        assertEquals(0.097, PocketServo.windowChaseRate(10.0, 4.0, 11.746, 4.0, 1.0, 0.0, 18), EPSILON,
                "closing displacement / gap, on-axis");
        // Moving AWAY from the victim (against the axis) reads negative — the w-tap
        // back-off phase the old pre-hit trend mistook for the whole window.
        assertEquals(-0.10, PocketServo.windowChaseRate(10.0, 4.0, 9.0, 4.0, 1.0, 0.0, 10), EPSILON);
        // Off-axis displacement projects: (0, 1.8) on axis (0.6, 0.8) over 12 → 1.44/12.
        assertEquals(0.12, PocketServo.windowChaseRate(0.0, 0.0, 0.0, 1.8, 0.6, 0.8, 12), EPSILON);
        // A degenerate gap is no window at all.
        assertTrue(Double.isNaN(PocketServo.windowChaseRate(0.0, 0.0, 1.0, 0.0, 1.0, 0.0, 0)));
        // The per-combo EMA rides the same seeded blend as the V2 chase EMA: a NaN
        // prior seeds to the first window; 0.3·0.15 + 0.7·0.097 = 0.1129 thereafter.
        assertEquals(0.097, PocketServo.chaseEma(Double.NaN, 0.097), EPSILON, "first window seeds");
        assertEquals(0.1129, PocketServo.chaseEma(0.097, 0.15), EPSILON, "0.3·0.15 + 0.7·0.097");
    }

    @Test
    void chaseEmaSeedsThenBlends() {
        // repair #2: NaN prior seeds to the instantaneous rate; then α=0.3 blend.
        assertEquals(0.30, PocketServo.chaseEma(Double.NaN, 0.30), EPSILON, "first hit seeds");
        double blended = PocketServo.CHASE_EMA_ALPHA * 0.50 + (1.0 - PocketServo.CHASE_EMA_ALPHA) * 0.30;
        assertEquals(blended, PocketServo.chaseEma(0.30, 0.50), EPSILON, "0.3·0.5 + 0.7·0.3 = 0.36");
        assertEquals(0.36, blended, 1.0e-9);
    }

    @Test
    void dynamicTargetFallsBackToAnchorWithoutFacing() {
        // No facing → the anchor is the whole answer (the journaled-but-anchored default).
        PredictorInputs noYaw = new PredictorInputs(
                true, 0.6, 0.6, 0.0, 0.0, 0.2806, 0.10,
                -1, -1, Double.NaN, Double.NaN, Double.NaN, 0.0, 0);
        PocketServo.Solution s = PocketServo.solve(SERVO, noYaw, 2.35, 0.0, 0.4, 0.35716, 0.10);
        assertEquals(SERVO.target(), s.dynamicTarget(), 0.0, "NaN yaw → dynamic target == anchor");
    }

    @Test
    void outDriftingVictimKeepsTheAnchor() {
        // repair #2: closing ≤ 0 (the knock still out-runs the chase at the terminal
        // tick) keeps the pull-in anchor, never relaxes toward the cap.
        double[] pi = signatureSchedule();
        // Weak chase, full signature knock → closingEnd < 0.
        PocketServo.DynResult r = PocketServo.dynamicTarget(
                WIDE, facingV2(90.0, Double.NaN, 40, Double.NaN, Double.NaN),
                10, 0, 0.35716, /*chase*/0.10, /*shippedH1*/0.845, /*drift*/0.0, pi);
        assertTrue(r.closingEnd() < 0.0, "the victim is out-drifting: " + r.closingEnd());
        assertEquals(WIDE.target(), r.target(), EPSILON, "out-drift → the anchor");
    }

    @Test
    void exposureIsMonotoneNonIncreasingInTarget() {
        // repair #1: e(T) never rises as T grows (a farther-launched victim sits inside
        // the answer envelope for no more terminal ticks). The premise the min-rule needs.
        double[] pi = signatureSchedule();
        double prev = Double.POSITIVE_INFINITY;
        for (double t = 2.30; t <= 2.95 + 1.0e-9; t += 0.01) {
            double e = PocketServo.exposure(t, 10, 0, 0.35716, 0.0, PocketServo.EYE_HEIGHT,
                    /*chaseEma*/0.50, /*shippedH1*/0.0, /*drift*/0.0, pi);
            assertTrue(e <= prev + 1.0e-9, "e nondecreasing at T=" + t + " (" + e + " > " + prev + ")");
            prev = e;
        }
    }

    @Test
    void minSelectionEmptySetFallsBackToCapEff() {
        // repair #1: no T bounds the exposure (tiny budget) → capEff, the least exposed.
        double[] pi = signatureSchedule();
        double chaseEma = 0.50;
        double capEff = PocketServo.capEff(WIDE, chaseEma);
        PocketServo.DynResult r = PocketServo.dynamicTarget(
                WIDE, facingV2(30.0, Double.NaN, 10, Double.NaN, Double.NaN),
                10, 0, 0.35716, chaseEma, /*shippedH1*/0.0, 0.0, pi);
        assertEquals(0.1, r.tAllow(), EPSILON);
        double eCap = PocketServo.exposure(capEff, 10, 0, 0.35716, 0.0, PocketServo.EYE_HEIGHT,
                chaseEma, 0.0, 0.0, pi);
        assertTrue(eCap > r.tAllow(), "even capEff is over budget → infeasible");
        assertEquals(capEff, r.target(), EPSILON, "infeasible → capEff (least-exposed)");
    }

    @Test
    void minSelectionWithinBudgetReturnsAnchor() {
        // repair #1: even the anchor already fits the budget (huge tAllow) → the anchor.
        double[] pi = signatureSchedule();
        double chaseEma = 0.50;
        PocketServo.DynResult r = PocketServo.dynamicTarget(
                WIDE, facingV2(150.0, 10.0, 800, Double.NaN, Double.NaN),
                10, 0, 0.35716, chaseEma, /*shippedH1*/0.0, 0.0, pi);
        double eAnchor = PocketServo.exposure(WIDE.target(), 10, 0, 0.35716, 0.0,
                PocketServo.EYE_HEIGHT, chaseEma, 0.0, 0.0, pi);
        assertTrue(eAnchor <= r.tAllow(), "the anchor is within budget");
        assertEquals(WIDE.target(), r.target(), EPSILON, "within budget → the anchor");
    }

    @Test
    void minSelectionInteriorCrossingBisects() {
        // repair #1: a budget strictly between e(capEff) and e(anchor) selects the
        // interior crossing e(T*) == tAllow — the honest min, found by bisection.
        double[] pi = signatureSchedule();
        double chaseEma = 0.50;
        // tAllow 1.5 comes from yaw 90 (turn 1.0, NaN rate) + rttV 50 (tPing 0.5).
        PredictorInputs in = facingV2(90.0, Double.NaN, 50, Double.NaN, Double.NaN);
        PocketServo.DynResult r = PocketServo.dynamicTarget(
                WIDE, in, 10, 0, 0.35716, chaseEma, /*shippedH1*/0.0, 0.0, pi);
        assertEquals(1.5, r.tAllow(), EPSILON);
        double capEff = PocketServo.capEff(WIDE, chaseEma);
        assertTrue(r.target() > WIDE.target() + 1.0e-6 && r.target() < capEff - 1.0e-6,
                "the solution is strictly interior: " + r.target());
        double eAt = PocketServo.exposure(r.target(), 10, 0, 0.35716, 0.0, PocketServo.EYE_HEIGHT,
                chaseEma, 0.0, 0.0, pi);
        assertEquals(r.tAllow(), eAt, 1.0e-4, "e(T*) == tAllow at the crossing");
        double eJustBelow = PocketServo.exposure(r.target() - 0.02, 10, 0, 0.35716, 0.0,
                PocketServo.EYE_HEIGHT, chaseEma, 0.0, 0.0, pi);
        assertTrue(eJustBelow > r.tAllow(), "it is the MINIMUM feasible T (below it is over budget)");
    }

    @Test
    void facingOrdersTheTargetFacedAwayLowFacingHigh() {
        // The three journal examples' INTENT, recomputed from the final constants in an
        // open-interval regime: faced-away+laggy relaxes toward the anchor, a facing
        // low-ping victim is pushed to capEff, perpendicular lands between. (At the
        // signature knock scale this interval collapses to the anchor — see the
        // shipped-anchor test; the ordering here is the mechanism, exercised open.)
        double[] pi = signatureSchedule();
        double chaseEma = 0.50;
        double capEff = PocketServo.capEff(WIDE, chaseEma);
        double facedAway = PocketServo.dynamicTarget(
                WIDE, facingV2(150.0, 10.0, 80, Double.NaN, Double.NaN),
                10, 0, 0.35716, chaseEma, 0.0, 0.0, pi).target();
        double perpendicular = PocketServo.dynamicTarget(
                WIDE, facingV2(90.0, Double.NaN, 40, Double.NaN, Double.NaN),
                10, 0, 0.35716, chaseEma, 0.0, 0.0, pi).target();
        double facing = PocketServo.dynamicTarget(
                WIDE, facingV2(30.0, Double.NaN, 10, Double.NaN, Double.NaN),
                10, 0, 0.35716, chaseEma, 0.0, 0.0, pi).target();
        assertEquals(WIDE.target(), facedAway, EPSILON, "faced-away high-ping → the anchor");
        assertEquals(capEff, facing, EPSILON, "facing low-ping → capEff-bound");
        assertTrue(perpendicular > facedAway + 1.0e-6 && perpendicular < facing - 1.0e-6,
                "perpendicular lands strictly between: " + perpendicular);
    }

    @Test
    void chaseEmaAndSlewKillTheCoinFlip() {
        // repair #2: the v1 cliff moved the target by the full 0.20 on ±chase noise.
        // With the EMA + the ≤0.05/hit slew, an oscillating chase can never move the
        // EMITTED target more than the slew limit between consecutive hits.
        double[] pi = signatureSchedule();
        double[] noisyChase = {0.20, 0.60, 0.22, 0.58, 0.25, 0.55, 0.30, 0.50, 0.35, 0.45};
        double priorEma = Double.NaN;
        double priorTarget = Double.NaN;
        double last = Double.NaN;
        for (double chase : noisyChase) {
            PredictorInputs in = facingV2(90.0, Double.NaN, 50, priorEma, priorTarget);
            PocketServo.DynResult r = PocketServo.dynamicTarget(
                    WIDE, in, 10, 0, 0.35716, chase, /*shippedH1*/0.0, 0.0, pi);
            if (!Double.isNaN(last)) {
                assertTrue(Math.abs(r.target() - last) <= PocketServo.TARGET_SLEW_LIMIT + EPSILON,
                        "the target cannot jump the old cliff: Δ=" + Math.abs(r.target() - last));
            }
            last = r.target();
            priorEma = r.chaseEma();
            priorTarget = r.target();
        }
    }

    @Test
    void shippedAnchorCollapsesDynamicToTheAnchor() {
        // The honest recomputation of the journal examples at the SHIPPED constants:
        // anchor 2.85 + signature chase makes capEff ≤ the anchor (the flatness the
        // verifier predicted), so V2 rides the anchor — DYNAMIC ≡ ANCHOR at signature
        // scale, exactly the feel verdict's "an honest ~2.85 target".
        PocketServoConfig shipped = PocketServoConfig.of(
                2.85, 1.0, 0.8, 1.2, 10, TargetMode.DYNAMIC, 2.95);
        double[] pi = signatureSchedule();
        for (double yaw : new double[] {30.0, 90.0, 150.0}) {
            PocketServo.DynResult r = PocketServo.dynamicTarget(
                    shipped, facingV2(yaw, Double.NaN, 40, Double.NaN, Double.NaN),
                    10, 0, 0.35716, /*chase*/0.2806, /*shippedH1*/0.845, 0.0, pi);
            assertEquals(2.85, r.target(), EPSILON, "capEff collapses to the anchor at signature scale");
        }
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
        // Chase.
        double chaseRate = !Double.isNaN(in.chaseAlongAxis())
                ? in.chaseAlongAxis()
                : 0.2806 * ((attrA > 0 ? attrA : 0.10) / 0.10);
        return d0 + knockTravel + driftTravel + tail - chaseRate * wPrime;
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

    /** A facing/ping/per-combo-memory input for the V2 dynamic-target pins. */
    private static PredictorInputs facingV2(
            double yawDeg, double yawRate, int rttV, double priorEma, double priorTarget) {
        return new PredictorInputs(
                true, 0.6, 0.6, 0.0, 0.0, Double.NaN, 0.10,
                -1, rttV, Double.NaN, Double.NaN, yawDeg, yawRate, 0, priorEma, priorTarget);
    }

    /** The σ-invariant Π(k) schedule for a grounded signature 10-tick flight (tLand 10). */
    private static double[] signatureSchedule() {
        double[] pi = new double[10];
        double p = 1.0;
        double sq = 0.6 * Q; // stone ground drag 0.546
        for (int k = 1; k <= 10; k++) {
            pi[k - 1] = p;
            boolean groundedPre = (k == 1) || (k > 10); // grounded launch, airborne through the window
            p *= (groundedPre ? sq : Q);
        }
        return pi;
    }
}
