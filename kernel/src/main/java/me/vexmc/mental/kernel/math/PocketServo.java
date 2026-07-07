package me.vexmc.mental.kernel.math;

/**
 * The pocket servo (combo-hold §3.2/§3.2b): the factor {@code σ} that scales the
 * fresh melee horizontal knock so the victim lands at a chosen separation from the
 * attacker — held in the un-retaliatable pocket, one swing from now. This is not a
 * proportional nudge; it is the <b>exact inverse solve of the era flight
 * equations</b> (owner directive 2026-07-04: the victim must land in a very
 * specific position), then clamped.
 *
 * <h2>The v1 solve (byte-identical, still the fallback seam)</h2>
 * <p>After a knock the victim carries {@code residualCarry + σ·freshEra} of
 * horizontal speed along the attacker→victim axis (the ledger residual is never
 * scaled — the A3 law), and each tick that speed advances the position and then
 * decays. Over a window of {@code w} ticks the victim travels {@code v0 · dragSum};
 * the sprinting attacker closes {@code chase}; the servo picks {@code σ*} so
 * {@code dNext == target}. The four-input v1 form ({@link #sigma(PocketServoConfig,
 * double, double, double, double, double)}) uses the pure air-drag geometric sum
 * and assumes a ground-level launch — kept verbatim for the v1 pins and the
 * degenerate short-circuits.</p>
 *
 * <h2>The precision solve (§3.2b; precision-derivation)</h2>
 * <p>{@link #solve} extends the SAME affine inversion with, per the derivation:</p>
 * <ul>
 *   <li>the <b>launch-ground-state drag branch</b> (§1.2, MANDATORY — the pure-air
 *       sum overshoots a grounded launch by ~1.9 blocks): the drag schedule Π
 *       folds one ground-drag decay on a grounded launch tick, then air drag, then
 *       a ground tail after touchdown;</li>
 *   <li><b>victim self-drift</b> (§2): the estimated held input, axis-projected,
 *       accumulated over the window as extra σ-independent travel;</li>
 *   <li><b>ping horizons</b> (§4): the attacker half-RTT shaves the judgment
 *       horizon {@code t*}; the victim half-RTT shifts the arc (and its touchdown)
 *       late;</li>
 *   <li>the <b>ground tail</b> (§5): grounded ticks inside the window fold the
 *       victim's own locomotion (their move-speed attribute) into the drift and the
 *       knock's ground-drag tail into the schedule;</li>
 *   <li>the <b>exposure-budget dynamic target</b> (§3), behind the config's
 *       {@link TargetMode} — anchor by default, the computed value journaled to the
 *       debug sink for the lab round.</li>
 * </ul>
 * <p>Ice landings ({@code landingSlip > 0.7}) decline the servo entirely
 * (σ = 1) — the pocket geometry itself changes there (§6 issue 6).</p>
 *
 * <h2>The predictor is one swappable seam</h2>
 * <p>Everything that turns flight geometry into "separation per unit shipped
 * speed" lives in {@link #predict}, which returns an affine {@link
 * FlightPrediction}. Both the v1 and the precision solve invert the same affine
 * form; the precision round grew only {@code predict} (and the drag schedule it
 * folds), never the inversion.</p>
 */
public final class PocketServo {

    /**
     * Era sprint ground speed in blocks/tick — the attacker's closing rate at
     * base speed (vanilla 1.8 sprint ≈ 5.6 m/s = 0.2806 b/t). Scaled by the
     * attacker's walk-normalized movement-speed attribute over the 0.10 baseline,
     * so Speed III closes 1.6× as fast. The fallback chase model when no measured
     * attacker-velocity trend is available (§1.4).
     */
    public static final double SPRINT_GROUND_SPEED = 0.2806;

    /** The walk-stance movement-speed baseline (0.1 player base) — the chase-factor denominator. */
    public static final double WALK_BASELINE = 0.10;

    /** Below this the fresh knock or the flight window is effectively nil; the servo declines (σ = 1). */
    private static final double EPSILON = 1.0e-9;

    /** A guard so a pathological vertical stamp can never spin the air-time sim forever. */
    private static final int MAX_AIR_TICKS = 1200;

    /* ── precision-round constants (precision-derivation) ─────────────────── */

    /** Air drag {@code q} (§0). */
    private static final double Q = Decay.AIR_DRAG;

    /** The client's ×0.98 input damping applied to grounded locomotion (§1.4/§5). */
    private static final double INPUT_DAMPING = 0.98;

    /** Below this air-time the flight is too brief to shape — the servo declines (§1.3). */
    private static final int MIN_AIR_TICKS_FOR_SERVO = 3;

    /** A landing-segment slipperiness past this is ice-class — decline (§6 issue 6). */
    public static final double ICE_DECLINE_SLIP = 0.7;

    /** Vanilla player height (head-top above feet) — the attacker's answer geometry (§3.1). */
    public static final double PLAYER_HEIGHT = 1.8;

    /** Vanilla standing eye height above feet — the victim's answer geometry (§3.1). */
    public static final double EYE_HEIGHT = 1.62;

    /** The victim's practical answer reach (§3.1, RV ≈ 2.9 — a lab-calibration constant). */
    public static final double VICTIM_REACH = 2.9;

    /** The AABB half-width the two players' reach requirements cancel over (§3.1). */
    public static final double HALF_WIDTH = 0.3;

    /* ── V2 dynamic-target constants (target-v2; the forensics/verifier repairs) ─ */

    /**
     * The attacker's practical reach along the axis (repair #3) — {@code capEff}'s
     * base. The eye→AABB margin the anchor's 2.95 basis folds in is carried by
     * {@link #LANDING_SLACK}, so this is the flat era survival reach, not the
     * inflated eye→AABB 3.3.
     */
    public static final double HIT_EDGE = 3.0;

    /**
     * The servo's own in-band landing p95 (repair #3, lab VERDICT-cited ≈ 0.17):
     * one-tick cadence-jitter slack subtracted from {@link #HIT_EDGE} so a
     * chase-aware {@code capEff} never asks for a separation the jitter can't hold.
     */
    public static final double LANDING_SLACK = 0.17;

    /**
     * The per-combo chase EMA weight α (repair #2, ≈ 0.3): the dynamic-target path
     * reads this smoothed chase, killing the v1 knife-edge coin-flip the noisy
     * 3-delta estimate drove. The σ* placement solve keeps the INSTANTANEOUS chase.
     */
    public static final double CHASE_EMA_ALPHA = 0.3;

    /**
     * The emitted dynamic-target slew limit per hit (repair #2, ≤ 0.05): the same
     * inputs perturbed by ±0.05 of chase noise move the target ≤ this — the cliff
     * coin-flip cannot resurface through the smoothed cap either.
     */
    public static final double TARGET_SLEW_LIMIT = 0.05;

    /** Below this |yawVsAxis| the victim needs no turn to answer (repair #4). */
    private static final double TURN_FREE_YAW = 60.0;

    /**
     * The conservative yaw-slew divisor floor (deg/tick, repair #4): a slow or
     * unknown turner is bounded here (a faster measured flick divides by its own,
     * larger rate → a smaller turn cost), so {@code turn} is honestly boundable.
     */
    private static final double TURN_RATE_FLOOR = 30.0;

    /** Real-valued victim ping ticks per ms of RTT (repair #4): {@code tPing = rtt/100 = (rtt/2)/50}, NO floor. */
    private static final double PING_TICKS_PER_MS = 1.0 / 100.0;

    /** A closing-rate floor so a near-zero terminal closing cannot blow up an exposure fraction. */
    private static final double CLOSING_FLOOR = 1.0e-4;

    /** The exposure-integral min-selection bisection tolerance / iteration cap (repair #1). */
    private static final double TARGET_BISECT_TOLERANCE = 1.0e-7;
    private static final int TARGET_BISECT_ITERS = 40;

    private PocketServo() {}

    /* ====================================================================== */
    /*  v1 solve — byte-identical, still the four-input fallback              */
    /* ====================================================================== */

    /**
     * The clamped exact-inverse servo factor (v1, four inputs). Returns exactly
     * {@code 1.0} when the config is inactive, when there is no fresh horizontal to
     * scale, or when the flight window collapses to zero — every path that cannot
     * honestly steer the victim leaves the era stamp untouched. Kept verbatim as
     * the pure-air-drag fallback and the v1 pin surface; production callers use the
     * precision {@link #sigma(PocketServoConfig, PredictorInputs, double, double,
     * double, double, double)} overload.
     */
    public static double sigma(
            PocketServoConfig config,
            double d0,
            double residualCarry,
            double freshEra,
            double attackerNormalizedSpeed,
            double verticalStampShipped) {
        if (!config.active()) {
            return 1.0;
        }
        if (!(freshEra > EPSILON)) {
            return 1.0; // nothing fresh to scale — the servo has no lever
        }
        FlightPrediction prediction = predict(config, d0, attackerNormalizedSpeed, verticalStampShipped);
        double slope = prediction.slope();
        if (!(slope > EPSILON)) {
            return 1.0; // window' == 0: the victim lands this tick, no travel to shape
        }
        double sigmaStar =
                (config.target() - prediction.constant() - slope * residualCarry) / (freshEra * slope);
        double blended = 1.0 + config.gain() * (sigmaStar - 1.0);
        return Math.max(config.min(), Math.min(config.max(), blended));
    }

    /**
     * The v1 predictor — {@code slope = dragSum} (pure air-drag geometric sum),
     * {@code constant = d0 − chase}. Retained for the v1 pins and the pure-air
     * fallback; the precision predictor is {@link #predict(PocketServoConfig,
     * PredictorInputs, double, double, double, double, double)}.
     */
    public static FlightPrediction predict(
            PocketServoConfig config,
            double d0,
            double attackerNormalizedSpeed,
            double verticalStampShipped) {
        int window = effectiveWindow(config.windowTicks(), verticalStampShipped);
        double dragSum = dragSum(window);
        double chase = SPRINT_GROUND_SPEED * chaseFactor(attackerNormalizedSpeed) * window;
        return new FlightPrediction(d0 - chase, dragSum, window);
    }

    /** {@code min(windowTicks, airTime(verticalStamp))} — the v1 flight window cut at touchdown. */
    public static int effectiveWindow(int windowTicks, double verticalStampShipped) {
        return Math.min(windowTicks, airTime(verticalStampShipped));
    }

    /**
     * The number of ticks a launch of {@code verticalStamp} (from ground level)
     * stays airborne, by the kernel's own tick simulation. v1 launch-from-ground
     * assumption; the precision path uses {@link #airTime(double, double)}.
     */
    public static int airTime(double verticalStamp) {
        return airTime(verticalStamp, 0.0);
    }

    /** The era air-drag geometric sum {@code Σ_{k=0}^{window-1} 0.91^k}; zero for an empty window. */
    public static double dragSum(int window) {
        if (window <= 0) {
            return 0.0;
        }
        return (1.0 - Math.pow(Q, window)) / (1.0 - Q);
    }

    /** The attacker's closing multiplier: normalized speed over the walk baseline (unavailable ⇒ 1.0). */
    public static double chaseFactor(double attackerNormalizedSpeed) {
        double attr = attackerNormalizedSpeed > 0.0 ? attackerNormalizedSpeed : WALK_BASELINE;
        return attr / WALK_BASELINE;
    }

    /* ====================================================================== */
    /*  precision solve (§3.2b) — the launch branch, drift, ping, tail, target */
    /* ====================================================================== */

    /**
     * The full per-hit solve result — the applied σ plus every intermediate the
     * debug sink logs for the lab round (§7.2). Directional quantities are
     * axis-projected. {@code predictedDNext} is where the solve expects the victim
     * to land at the next swing (== {@code target} for an unclamped hit).
     */
    public record Solution(
            double sigma, double sigmaStar,
            double target, double dynamicTarget, double anchor,
            int windowTicks, int horizonTicks, int shiftVictimTicks, int airTicks, int landTick,
            double dragSum, double driftTravel, double chaseTravel, double chaseRate, boolean chaseMeasured,
            double d0, double residualCarry, double freshEra, double verticalStamp,
            boolean launchGrounded, double launchSlip, boolean declined,
            double predictedDNext,
            double chaseEma, double tAllow, double turn, double closingEnd) {
    }

    /**
     * One V2 dynamic-target evaluation (target-v2 repairs #1–#4): the chosen {@code
     * target} (already EMA-smoothed and slew-limited), the advanced per-combo {@code
     * chaseEma} the caller persists for the next hit, and the debug quantities
     * ({@code tAllow}, {@code turn}, {@code closingEnd}) the forensics gap named.
     */
    public record DynResult(double target, double chaseEma, double tAllow, double turn, double closingEnd) {
    }

    /**
     * The precision servo factor for one melee hit (§3.2b). Delegates to
     * {@link #solve} and returns its σ; the engine journals this value and the core
     * pushes the full {@link Solution} to the debug sink.
     */
    public static double sigma(
            PocketServoConfig config, PredictorInputs inputs,
            double d0, double residualCarry, double freshEra,
            double verticalStampShipped, double attackerNormalizedSpeed) {
        return solve(config, inputs, d0, residualCarry, freshEra,
                verticalStampShipped, attackerNormalizedSpeed).sigma();
    }

    /**
     * The precision inverse solve — folds the launch-branch drag schedule, victim
     * self-drift, ground-tail locomotion, ping horizons and the (optional) dynamic
     * target into the affine prediction, then inverts it for σ (§3.2b;
     * precision-derivation §8). Every degenerate case (inactive, no fresh lever,
     * air-time too brief, ice landing, collapsed window) declines to σ = 1.
     */
    public static Solution solve(
            PocketServoConfig config, PredictorInputs inputs,
            double d0, double residualCarry, double freshEra,
            double verticalStampShipped, double attackerNormalizedSpeed) {
        double anchor = config.target();
        if (!config.active() || !(freshEra > EPSILON)) {
            return declined(config, d0, residualCarry, freshEra, verticalStampShipped, inputs, anchor);
        }
        Fold fold = simulate(config, inputs, d0, residualCarry, freshEra,
                verticalStampShipped, attackerNormalizedSpeed);
        if (fold.declined || !(fold.dragSum > EPSILON)) {
            return declined(config, d0, residualCarry, freshEra, verticalStampShipped, inputs, anchor);
        }
        double slope = fold.dragSum;
        double sigmaStar = (fold.target - fold.constant - slope * residualCarry) / (freshEra * slope);
        double blended = 1.0 + config.gain() * (sigmaStar - 1.0);
        double sigma = Math.max(config.min(), Math.min(config.max(), blended));
        double predictedDNext = fold.constant + slope * (residualCarry + sigma * freshEra);
        return new Solution(
                sigma, sigmaStar, fold.target, fold.dynamicTarget, anchor,
                fold.wPrime, fold.tStar, fold.shiftV, fold.airTime, fold.tLand,
                fold.dragSum, fold.driftTravel, fold.chaseTravel, fold.chaseRate, fold.chaseMeasured,
                d0, residualCarry, freshEra, verticalStampShipped,
                inputs.launchGrounded(), inputs.launchSlip(), false, predictedDNext,
                fold.chaseEma, fold.tAllow, fold.turn, fold.closingEnd);
    }

    /**
     * The precision predictor — the {@link FlightPrediction} affine seam with the
     * launch branch, drift, ping horizons, ground tail and resolved target folded
     * in. A declined hit returns a zero-slope prediction (the inversion then yields
     * σ = 1).
     */
    public static FlightPrediction predict(
            PocketServoConfig config, PredictorInputs inputs,
            double d0, double residualCarry, double freshEra,
            double verticalStampShipped, double attackerNormalizedSpeed) {
        Fold fold = simulate(config, inputs, d0, residualCarry, freshEra,
                verticalStampShipped, attackerNormalizedSpeed);
        double slope = fold.declined ? 0.0 : fold.dragSum;
        return new FlightPrediction(fold.constant, slope, fold.wPrime, fold.target);
    }

    /* ── the flight fold (§0 model, tick by tick) ─────────────────────────── */

    /** The intermediate values of one precision solve — the debug sink and pin surface. */
    private record Fold(
            boolean declined, int wPrime, int tStar, int shiftV, int airTime, int tLand,
            double dragSum, double driftTravel, double chaseRate, double chaseTravel, boolean chaseMeasured,
            double target, double dynamicTarget, double constant,
            double chaseEma, double tAllow, double turn, double closingEnd) {
    }

    private static Fold simulate(
            PocketServoConfig config, PredictorInputs inputs,
            double d0, double residualCarry, double freshEra,
            double verticalStampShipped, double attackerNormalizedSpeed) {

        double anchor = config.target();
        int cadence = config.windowTicks();
        // Ping horizons (§4): attacker half-RTT shaves t* (round-to-nearest); victim
        // half-RTT shifts the arc late (floor — conservative in the safety direction).
        int shiftA = inputs.attackerRttMillis() >= 0
                ? (int) Math.round((inputs.attackerRttMillis() / 2.0) / 50.0)
                : 0;
        int shiftV = inputs.victimRttMillis() >= 0
                ? (int) Math.floor((inputs.victimRttMillis() / 2.0) / 50.0)
                : 0;
        int tStar = cadence - shiftA;
        int airTime = airTime(verticalStampShipped, inputs.launchHeight());
        // Degenerate / ice guards → decline (σ = 1). w' is the horizon; the arc's
        // touchdown (shifted late by shiftV) sits at tLand inside the fold.
        if (airTime < MIN_AIR_TICKS_FOR_SERVO || tStar < 1
                || inputs.landingSlip() > ICE_DECLINE_SLIP) {
            return new Fold(true, Math.max(0, tStar), tStar, shiftV, airTime,
                    shiftV + airTime, 0.0, 0.0, 0.0, 0.0, false, anchor, anchor, d0,
                    Double.NaN, 0.0, 0.0, 0.0);
        }
        int wPrime = tStar;
        int tLand = shiftV + airTime;
        double sqLaunch = inputs.launchSlip() * Q;
        double sqLand = inputs.landingSlip() * Q;

        // (1) The KNOCK horizontal drag schedule Σ Π (§1.2/§5): the launch tick uses
        // the captured ground state (a grounded launch decays once at ground drag —
        // the #1 trap), then air drag, then the knock's own ground-drag tail after
        // touchdown (folded in exactly — the §5 first bullet).
        double pi = 1.0;              // Π(k), the knock move factor at tick k
        double dragSum = 0.0;         // Σ Π — separation per unit shipped speed
        double[] piByTick = new double[wPrime]; // Π(k) per tick — the V2 exposure's σ-invariant seam
        for (int k = 1; k <= wPrime; k++) {
            piByTick[k - 1] = pi;
            dragSum += pi;
            boolean groundedPre = (k == 1) ? inputs.launchGrounded() : (k > tLand);
            double drag = groundedPre ? (k == 1 ? sqLaunch : sqLand) : Q;
            pi *= drag;
        }

        // (2) Victim self-drift (§2.5/§8): the estimated held input, axis-projected,
        // accumulated as the air double-sum S2 over the window (rebuilding from 0 —
        // the stamp wiped the drift VELOCITY, not the held input).
        double driftTravel = inputs.driftAlongAxis() * s2(wPrime);

        // (3) Ground-tail locomotion (§5): the G grounded ticks inside the window let
        // the victim WALK — their OWN move-speed attribute (not the air 0.02 rate),
        // rebuilt over G ticks at ground drag, in the held-input's direction.
        int groundTicks = Math.max(0, tStar - shiftV - airTime);
        double groundAttr = inputs.victimNormalizedSpeed() > 0.0
                ? inputs.victimNormalizedSpeed() : WALK_BASELINE;
        double tail = groundTicks > 0 && inputs.driftAlongAxis() != 0.0
                ? Math.signum(inputs.driftAlongAxis())
                        * groundLocomotion(groundTicks, sqLand, INPUT_DAMPING * groundAttr)
                : 0.0;

        // (4) Chase — the measured attacker-velocity trend, or the 0.2806×attr model.
        double chaseRate;
        boolean chaseMeasured;
        if (!Double.isNaN(inputs.chaseAlongAxis())) {
            chaseRate = inputs.chaseAlongAxis();
            chaseMeasured = true;
        } else {
            chaseRate = SPRINT_GROUND_SPEED * chaseFactor(attackerNormalizedSpeed);
            chaseMeasured = false;
        }
        double chaseTravel = chaseRate * wPrime;

        // (5) The V2 exposure-budget dynamic target (§3; target-v2 repairs #1–#4),
        // or the anchor (the shipped default). The σ=1 shipped horizontal (R + F,
        // NOT the σ* solve's) and the air drift rate feed the per-tick terminal
        // closing the exposure integral walks; the σ* solve keeps the instantaneous
        // chase (repair #2 — the lab-validated placement math is untouched).
        DynResult dyn = dynamicTarget(
                config, inputs, wPrime, shiftV, verticalStampShipped, chaseRate,
                residualCarry + freshEra, inputs.driftAlongAxis(), piByTick);
        double target = config.targetMode() == TargetMode.DYNAMIC ? dyn.target() : anchor;

        // constant = d0 − chase + drift + ground tail (all σ-independent); slope = Σ Π.
        double constant = d0 - chaseTravel + driftTravel + tail;
        return new Fold(false, wPrime, tStar, shiftV, airTime, tLand,
                dragSum, driftTravel + tail, chaseRate, chaseTravel, chaseMeasured,
                target, dyn.target(), constant,
                dyn.chaseEma(), dyn.tAllow(), dyn.turn(), dyn.closingEnd());
    }

    /**
     * The V2 exposure-budget dynamic target (§3.3; target-v2 repairs #1–#4). Picks
     * the LEAST-aggressive separation {@code T ∈ [anchor, capEff]} whose terminal
     * exposure integral {@code e(T)} stays within the victim's retaliation budget
     * {@code tAllow} — the honest {@code min}-selection (repair #1), NOT the
     * degenerate {@code max} that would just move the saturation to {@code capEff}.
     *
     * <p>Decisions, all documented:</p>
     * <ul>
     *   <li><b>closing ≤ 0 (out-drifting victim) → the ANCHOR</b> (repair #2): the
     *       combo is being lost outward; relaxing toward the cap would accelerate
     *       the loss, so the servo keeps its pull-in posture. This is the 69.6%
     *       dominant population the v1 gate also sent to the anchor.</li>
     *   <li><b>facing unavailable / collapsed cap interval → the ANCHOR</b>: no
     *       geometry to budget, so the anchor is the whole answer.</li>
     *   <li><b>no T bounds the exposure (infeasible) → {@code capEff}</b>, the
     *       least-exposed choice (repair #1's empty-set fallback); <b>even the
     *       anchor is within budget → the anchor</b> (already relaxed enough).</li>
     * </ul>
     *
     * <p>The chase enters through the per-combo EMA (repair #2) so the noisy
     * 3-delta estimate no longer coin-flips the target; the emitted target is
     * slew-limited to {@link #TARGET_SLEW_LIMIT}/hit. Always evaluated so the debug
     * sink can log it; only USED when {@link TargetMode#DYNAMIC}.</p>
     *
     * @param shippedHorizontalSigma1 the σ=1 shipped horizontal {@code R + F} (NOT
     *                                the σ* solve's) — the exposure's knock rate.
     * @param piByTick                {@code Π(k)} for {@code k = 1..wPrime}, the
     *                                σ-invariant per-tick drag the fold already walked.
     */
    public static DynResult dynamicTarget(
            PocketServoConfig config, PredictorInputs inputs,
            int wPrime, int shiftV, double verticalStampShipped,
            double chaseRate, double shippedHorizontalSigma1, double driftAlongAxis,
            double[] piByTick) {
        double anchor = config.target();
        // Repair #2: the dynamic-target path reads the EMA-smoothed chase (the σ*
        // solve keeps the instantaneous one); on the first hit the prior is NaN so
        // the EMA seeds to the instantaneous rate.
        double chaseEma = chaseEma(inputs.priorChaseEma(), chaseRate);
        double turn = turn(inputs.victimYawVsAxisDeg(), inputs.victimYawRateDegPerTick());
        double tAllow = tAllow(inputs.victimRttMillis(), inputs.victimYawVsAxisDeg(),
                inputs.victimYawRateDegPerTick());
        double eye = Double.isNaN(inputs.victimEyeHeight()) ? EYE_HEIGHT : inputs.victimEyeHeight();
        double capEff = capEff(config, chaseEma);
        // The terminal closing at w' (σ=1, EMA chase) — the out-drift gate and a debug field.
        double closingEnd = wPrime >= 1 && piByTick.length >= wPrime
                ? chaseEma - shippedHorizontalSigma1 * piByTick[wPrime - 1] - driftAlongAxis * geo(wPrime)
                : 0.0;

        double raw;
        if (Double.isNaN(inputs.victimYawVsAxisDeg())
                || wPrime < 1 || piByTick.length < wPrime
                || !(capEff > anchor + EPSILON)) {
            raw = anchor; // facing unavailable, degenerate window, or a collapsed [anchor, capEff]
        } else if (!(closingEnd > 0.0)) {
            raw = anchor; // repair #2: out-drifting victim keeps the pull-in anchor
        } else {
            raw = selectTarget(anchor, capEff, tAllow, wPrime, shiftV,
                    verticalStampShipped, inputs.launchHeight(), eye,
                    chaseEma, shippedHorizontalSigma1, driftAlongAxis, piByTick);
        }
        double target = slew(inputs.priorDynamicTarget(), raw, TARGET_SLEW_LIMIT);
        return new DynResult(target, chaseEma, tAllow, turn, closingEnd);
    }

    /**
     * The min-selection over the monotone exposure integral (repair #1): {@code
     * e(T)} is non-increasing in {@code T} (a farther-launched victim sits inside
     * the answer envelope for fewer terminal ticks), so the feasible set {@code
     * {T : e(T) ≤ tAllow}} is the upper interval {@code [T*, capEff]}. The minimum
     * over {@code [anchor, capEff]} is the anchor when it already fits the budget,
     * {@code capEff} when nothing does, else the crossing {@code T*} by bisection.
     */
    private static double selectTarget(
            double anchor, double capEff, double tAllow, int wPrime, int shiftV,
            double verticalStampShipped, double launchHeight, double eye,
            double chaseEma, double shippedHorizontalSigma1, double driftAlongAxis,
            double[] piByTick) {
        if (exposure(anchor, wPrime, shiftV, verticalStampShipped, launchHeight, eye,
                chaseEma, shippedHorizontalSigma1, driftAlongAxis, piByTick) <= tAllow) {
            return anchor; // even the least-launched target is within budget
        }
        if (exposure(capEff, wPrime, shiftV, verticalStampShipped, launchHeight, eye,
                chaseEma, shippedHorizontalSigma1, driftAlongAxis, piByTick) > tAllow) {
            return capEff; // infeasible — the least-exposed choice (empty-set fallback)
        }
        double lo = anchor; // e(lo) > tAllow
        double hi = capEff; // e(hi) ≤ tAllow
        for (int i = 0; i < TARGET_BISECT_ITERS && (hi - lo) > TARGET_BISECT_TOLERANCE; i++) {
            double mid = 0.5 * (lo + hi);
            double e = exposure(mid, wPrime, shiftV, verticalStampShipped, launchHeight, eye,
                    chaseEma, shippedHorizontalSigma1, driftAlongAxis, piByTick);
            if (e <= tAllow) {
                hi = mid; // mid feasible — the crossing is at or below it
            } else {
                lo = mid;
            }
        }
        return hi; // the smallest feasible T within tolerance
    }

    /**
     * The terminal-window exposure integral {@code e(T)} (repair #1): the summed
     * fractional time the victim sits inside their answer envelope over the terminal
     * ticks — those, walking back from {@code w'}, where the attacker is net-closing
     * ({@code closing > 0}). The trajectory is anchored at the landing separation
     * {@code T} and walked backward at the per-tick closing (built from the
     * σ-invariant {@code Π} schedule and the σ=1 shipped horizontal — so this is
     * independent of the σ* solve), which makes {@code e(T)} monotone non-increasing
     * in {@code T}. Each tick's fraction is {@code clamp01((h_safe − h)/closing)} —
     * the share of the tick the victim spends inside {@code h_safe(t)} before the
     * closing carries them out; an un-answerable tick ({@code Δ ≥ reach}) adds 0.
     */
    public static double exposure(
            double target, int wPrime, int shiftV,
            double verticalStampShipped, double launchHeight, double eye,
            double chaseEma, double shippedHorizontalSigma1, double driftAlongAxis,
            double[] piByTick) {
        double e = 0.0;
        double backSum = 0.0; // Σ closing over the terminal ticks already walked (T-independent)
        for (int t = wPrime; t >= 1; t--) {
            double closing = chaseEma - shippedHorizontalSigma1 * piByTick[t - 1]
                    - driftAlongAxis * geo(t);
            if (!(closing > 0.0)) {
                break; // the separation peak — the terminal answer window ends here
            }
            double h = target + backSum;
            double feetY = arcHeight(verticalStampShipped, launchHeight, Math.max(0, t - shiftV));
            double dEnd = Math.max(0.0, feetY + eye - PLAYER_HEIGHT);
            if (dEnd < VICTIM_REACH) {
                double hSafe = HALF_WIDTH + Math.sqrt(VICTIM_REACH * VICTIM_REACH - dEnd * dEnd);
                e += clamp01((hSafe - h) / Math.max(closing, CLOSING_FLOOR));
            } // else geometrically un-answerable this tick — no exposure
            backSum += closing;
        }
        return e;
    }

    /**
     * The retaliation turn cost in real-valued ticks (repair #4): {@code 0} within
     * {@link #TURN_FREE_YAW} of the axis, else {@code (|yaw| − 60)/max(yawRateHat,
     * 30°/tick)} — a genuinely faster measured flick divides by its own rate (a
     * smaller cost); a slow or unavailable ({@link Double#NaN}) rate falls back to
     * the conservative 30°/tick floor. Continuous, replacing the v1 {0,1,2} ladder.
     */
    public static double turn(double yawVsAxisDeg, double yawRateDegPerTick) {
        if (Double.isNaN(yawVsAxisDeg)) {
            return 0.0;
        }
        double yaw = Math.abs(yawVsAxisDeg);
        if (yaw <= TURN_FREE_YAW) {
            return 0.0;
        }
        double rate = Double.isNaN(yawRateDegPerTick)
                ? TURN_RATE_FLOOR
                : Math.max(yawRateDegPerTick, TURN_RATE_FLOOR);
        return (yaw - TURN_FREE_YAW) / rate;
    }

    /**
     * The victim's earliest-retaliation budget in real-valued ticks (repair #4):
     * {@code tPing + turn}, {@code tPing = rttV/100} (== half-RTT in ticks, NO
     * floor). An unavailable RTT ({@code < 0}) contributes no ping slack.
     */
    public static double tAllow(double victimRttMillis, double yawVsAxisDeg, double yawRateDegPerTick) {
        double tPing = victimRttMillis >= 0.0 ? victimRttMillis * PING_TICKS_PER_MS : 0.0;
        return tPing + turn(yawVsAxisDeg, yawRateDegPerTick);
    }

    /**
     * The chase-aware upper clamp (repair #3): {@code capEff = hitEdge − 0.5·chaseEma
     * − landingSlack}, clamped to {@code [anchor, hitCap]}. A faster chase tightens
     * the reachable cap (±1 tick of cadence jitter moves separation by ½ a chase
     * step); the {@code [anchor, hitCap]} clamp forbids the inverted interval a
     * literal {@link #HIT_EDGE} would otherwise produce (the verifier's hole #12).
     */
    public static double capEff(PocketServoConfig config, double chaseEma) {
        double raw = HIT_EDGE - 0.5 * chaseEma - LANDING_SLACK;
        return Math.max(config.target(), Math.min(config.hitCap(), raw));
    }

    /** The per-combo chase EMA advance (repair #2): a NaN prior seeds to the instantaneous rate. */
    public static double chaseEma(double priorChaseEma, double chaseRate) {
        return Double.isNaN(priorChaseEma)
                ? chaseRate
                : CHASE_EMA_ALPHA * chaseRate + (1.0 - CHASE_EMA_ALPHA) * priorChaseEma;
    }

    /**
     * The measured POST-hit window chase rate (b/t, + = closing): the attacker's
     * displacement since the previous servo hit, projected on the CURRENT
     * attacker→victim axis, per tick of the inter-hit gap.
     *
     * <p>This is the servo-lab 2.4.5 chase correction. The pre-hit instantaneous
     * velocity trend the §2-estimator-applied-to-the-attacker read (derivation §6
     * row 7 / issue 7) samples the WRONG PHASE of the attacker's cycle: at ring
     * time the attacker has arrived at range and idles or w-taps backward (the lab
     * measured it at ≈ 0 to negative), while the quantity the solve prices is the
     * RE-chase after the knock ships (+1.1…+1.7 blocks per window, every cell).
     * The just-completed inter-hit window is the same "measured attacker-velocity
     * trend" issue 7 prefers, evaluated over the horizon it actually prices — and
     * it folds the ×0.6 self-slow and the line deviation in for free, exactly the
     * two residuals row 7 wanted the measured estimator to recover. The attribute
     * model ({@link #SPRINT_GROUND_SPEED} × attr/0.10) stays the fallback for the
     * first window of a combo ({@link Double#NaN} here ⇒ the solve's model path).</p>
     */
    public static double windowChaseRate(
            double anchorX, double anchorZ, double x, double z,
            double ux, double uz, long gapTicks) {
        if (gapTicks < 1) {
            return Double.NaN;
        }
        return ((x - anchorX) * ux + (z - anchorZ) * uz) / gapTicks;
    }

    /** Slew-limit the emitted target to {@code ±limit} of the prior (repair #2); a NaN prior passes through. */
    private static double slew(double priorTarget, double raw, double limit) {
        if (Double.isNaN(priorTarget)) {
            return raw;
        }
        return Math.max(priorTarget - limit, Math.min(priorTarget + limit, raw));
    }

    private static double clamp01(double value) {
        return value < 0.0 ? 0.0 : (value > 1.0 ? 1.0 : value);
    }

    /* ── shared flight helpers ────────────────────────────────────────────── */

    /**
     * The number of ticks a launch of {@code verticalStamp} from {@code
     * launchHeight} above ground stays airborne, by the vanilla vertical
     * integration order (move by the current vy, then gravity + drag, until the
     * feet return to the ground). A non-positive stamp from ground level is already
     * grounded (0); a positive launch height with a descending stamp still counts
     * its fall.
     */
    public static int airTime(double verticalStamp, double launchHeight) {
        double y = launchHeight;
        double vy = verticalStamp;
        if (y <= 0.0 && !(vy > 0.0)) {
            return 0; // already on the ground, not rising
        }
        int ticks = 0;
        while (ticks < MAX_AIR_TICKS) {
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

    /**
     * The victim's feet height above launch ground after {@code ticks} of the
     * σ-invariant vertical arc (the same integration order as {@link #airTime}),
     * clamped at the ground once landed — the dynamic target's Δ(t) source.
     */
    public static double arcHeight(double verticalStamp, double launchHeight, int ticks) {
        double y = launchHeight;
        double vy = verticalStamp;
        for (int t = 0; t < ticks; t++) {
            y += vy;
            if (y <= 0.0) {
                return 0.0;
            }
            vy = (vy - Decay.DEFAULT_GRAVITY) * Decay.VERTICAL_DRAG;
            if (vy < -Decay.TERMINAL_VELOCITY) {
                vy = -Decay.TERMINAL_VELOCITY;
            }
        }
        return y;
    }

    /** The era air-drag geometric sum {@code geo(n) = Σ_{i=0}^{n-1} q^i} — the D_g/D_a primitive (§1.2). */
    public static double geo(int n) {
        return dragSum(n);
    }

    /**
     * The drift double-sum {@code S2(n) = Σ_{k=1}^{n} geo(k) = [n − q·geo(n)]/(1 − q)}
     * (§2.2) — the closed form the fold's airborne drift accumulates to; exposed for
     * the pins that hand-check it against the derivation's 42.514650.
     */
    public static double s2(int n) {
        if (n <= 0) {
            return 0.0;
        }
        return (n - Q * geo(n)) / (1.0 - Q);
    }

    /**
     * The grounded-launch drag schedule closed form {@code D_g(w) = 1 + sq·geo(w−1)}
     * (§1.2) — the fold's dragSum for a grounded launch that stays airborne through
     * the whole window; a pin cross-checks the tick fold against it.
     */
    public static double groundedLaunchDragSum(int window, double sq) {
        if (window <= 0) {
            return 0.0;
        }
        return 1.0 + sq * geo(window - 1);
    }

    /** The airborne-launch drag schedule closed form {@code D_a(w) = geo(w)} (§1.2). */
    public static double airborneLaunchDragSum(int window) {
        return geo(window);
    }

    /**
     * The victim's own ground-tail locomotion over {@code g} grounded ticks (§5):
     * {@code a_g · Σ_{k=1}^{g} (1 − sq^k)/(1 − sq)}, the displacement of a walk that
     * rebuilds from rest at ground drag {@code sq}. {@code a_g} is the victim's
     * client-damped move-speed per tick — ~5× the 0.02-class air steering, which is
     * why even a two-tick tail earns its own term.
     */
    public static double groundLocomotion(int g, double sq, double perTickInput) {
        if (g <= 0) {
            return 0.0;
        }
        double denominator = 1.0 - sq;
        double sum = 0.0;
        double sqPow = 1.0;
        for (int k = 1; k <= g; k++) {
            sqPow *= sq;
            sum += (1.0 - sqPow) / denominator;
        }
        return perTickInput * sum;
    }

    private static Solution declined(
            PocketServoConfig config, double d0, double residualCarry, double freshEra,
            double verticalStamp, PredictorInputs inputs, double anchor) {
        return new Solution(
                1.0, 1.0, anchor, anchor, anchor,
                0, 0, 0, 0, 0,
                0.0, 0.0, 0.0, 0.0, false,
                d0, residualCarry, freshEra, verticalStamp,
                inputs.launchGrounded(), inputs.launchSlip(), true, d0,
                Double.NaN, 0.0, 0.0, 0.0);
    }
}
