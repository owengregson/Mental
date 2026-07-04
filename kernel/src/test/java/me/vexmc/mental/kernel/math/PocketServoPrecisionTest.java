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

    /* ── the dynamic target function (§3.3) ────────────────────────────────── */

    @Test
    void turnCostBands() {
        assertEquals(0, PocketServo.turnCost(30.0, 0.0), "facing (<60°) → 0");
        assertEquals(1, PocketServo.turnCost(90.0, 0.0), "quarter-turn (<120°) → 1");
        assertEquals(2, PocketServo.turnCost(150.0, 0.0), "faced away (≥120°) → 2");
        assertEquals(0, PocketServo.turnCost(150.0, 40.0), "already flicking (≥30°/t) → 0 regardless");
        assertEquals(0, PocketServo.turnCost(Double.NaN, 40.0), "flicking overrides even a NaN facing");
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
    void dynamicTargetRelaxesBetweenAnchorAndHitCap() {
        // A facing, low-ping victim near touchdown (small Δ) pushes the target up
        // toward the hit cap; the value always clamps to [anchor, hitCap].
        double target = PocketServo.dynamicTarget(
                SERVO, facing(0.0, 0.0, 0), /*wPrime*/10, /*shiftV*/0, /*vstamp*/0.30,
                /*chaseRate*/0.2806, /*knockRateEnd*/0.02, /*driftRateEnd*/0.0);
        assertTrue(target >= SERVO.target() - EPSILON && target <= SERVO.hitCap() + EPSILON,
                "dynamic target stays within [anchor, hitCap]: " + target);
        // A faced-away, high-ping victim (large tAllow) relaxes the target toward the
        // anchor — strictly ≤ the facing case.
        double faced = PocketServo.dynamicTarget(
                SERVO, facing(150.0, 0.0, 200), 10, 2, 0.30, 0.2806, 0.02, 0.0);
        assertTrue(faced <= target + EPSILON, "a faced-away laggy victim relaxes toward the anchor");
    }

    @Test
    void dynamicTargetHonorsTheTAllowArithmetic() {
        // relaxed = safeNeed − tAllow·closing, clamped. Reconstruct safeNeed from the
        // arc and assert the pre-clamp arithmetic in the relaxing band.
        double vstamp = 0.30;
        int wPrime = 10;
        int shiftV = 0;
        double chaseRate = 0.35;
        double knockRateEnd = 0.02;
        double driftRateEnd = 0.0;
        double closing = chaseRate - knockRateEnd - driftRateEnd;
        double feetY = PocketServo.arcHeight(vstamp, 0.0, wPrime - shiftV);
        double dEnd = Math.max(0.0, feetY + PocketServo.EYE_HEIGHT - PocketServo.PLAYER_HEIGHT);
        double safeNeed = PocketServo.HALF_WIDTH
                + Math.sqrt(PocketServo.VICTIM_REACH * PocketServo.VICTIM_REACH - dEnd * dEnd);
        int tAllow = shiftV + PocketServo.turnCost(150.0, 0.0); // 0 + 2
        double expected = Math.max(SERVO.target(), Math.min(SERVO.hitCap(), safeNeed - tAllow * closing));
        double actual = PocketServo.dynamicTarget(
                SERVO, facing(150.0, 0.0, 0), wPrime, shiftV, vstamp, chaseRate, knockRateEnd, driftRateEnd);
        assertEquals(expected, actual, EPSILON);
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
        // Air drift over the window (S2) + ground-tail locomotion over G ticks.
        double driftTravel = in.driftAlongAxis() * bruteS2(wPrime);
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

    private static PredictorInputs facing(double yawDeg, double yawRate, int rttV) {
        return new PredictorInputs(
                true, 0.6, 0.6, 0.0, 0.0, Double.NaN, 0.10,
                -1, rttV, Double.NaN, Double.NaN, yawDeg, yawRate, 0);
    }
}
