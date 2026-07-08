package me.vexmc.mental.kernel.math;

/**
 * The pocket servo (combo-hold §3.2/§3.2b): the factor {@code σ} that scales the
 * fresh melee horizontal knock so the victim lands at a chosen separation from the
 * attacker — held in the un-retaliatable pocket, one swing from now. This is not a
 * proportional nudge; it is the <b>exact inverse solve of the era flight
 * equations</b> (owner directive 2026-07-04: the victim must land in a very
 * specific position), then clamped.
 *
 * <h2>The target: the answer-denial boundary (2.4.5 redesign)</h2>
 * <p>The steered separation is the <b>answer-denial boundary</b>: land the victim
 * right at the separation where, at the moment they could first swing back
 * ({@code t* = tPing + turn}), their reach-back is denied by a hair while the
 * attacker can still reach them. Push OUT to that boundary; pull IN only if the
 * natural knock would eject them past the attacker's own reach, and never below
 * {@code targetFloor}. The geometry ({@link #boundaryTarget}) is evaluated at the
 * victim's predicted arc height at {@code t*} ({@link #arcHeight}), folding in the
 * EFFECTIVE (possibly reach-handicapped) victim reach the caller supplies. When
 * the geometry is unmeasurable (no facing) the target degrades to the config's
 * {@code staticTarget}; {@link TargetMode#STATIC} pins it there unconditionally.
 * The pre-2.4.5 exposure-budget target — which MINIMISED toward the least
 * separation still un-answerable, dragging the victim into their own retaliation
 * range — is gone.</p>
 *
 * <h2>The v1 solve (byte-identical, still the fallback seam)</h2>
 * <p>After a knock the victim carries {@code residualCarry + σ·freshEra} of
 * horizontal speed along the attacker→victim axis (the ledger residual is never
 * scaled — the A3 law), and each tick that speed advances the position and then
 * decays. Over a window of {@code w} ticks the victim travels {@code v0 · dragSum};
 * the sprinting attacker closes {@code chase}; the servo picks {@code σ*} so
 * {@code dNext == target}. The four-input v1 form ({@link #sigma(PocketServoConfig,
 * double, double, double, double, double)}) uses the pure air-drag geometric sum
 * and assumes a ground-level launch, steering to the config {@code staticTarget}
 * (it has no facing geometry) — kept verbatim for the v1 pins and the degenerate
 * short-circuits.</p>
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
 *   <li>the <b>answer-denial-boundary target</b>, behind the config's
 *       {@link TargetMode}.</li>
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

    /**
     * The chase-alignment / effectiveness fraction (2.4.6 undershoot fix): the
     * share of full straight-line sprint a real combo attacker's close actually
     * realizes over the flight window — imperfect alignment to the axis plus the
     * per-hit w-tap dip (a real reset dips to ≈0.55× sprint and recovers in 3–4
     * ticks, dynamic-chase-movement-constants §3b). Used as the chase FLOOR for an
     * active combo: the measured post-hit window averages the whole inter-hit gap
     * and under-reads the post-knock chase burst, so without this floor σ* sinks
     * into the false-low min-clamp (the measured undershoot). Calibrated so σ*
     * centres near 1.0–1.1 at a typical mid-combo residual, landing inside the
     * {@code [0.93, 1.35]} clamp band (owner playtest, 2026-07-07). A genuinely
     * harder MEASURED chase still exceeds the floor and is used as-is.
     */
    public static final double CHASE_ALIGNMENT = 0.70;

    /**
     * The floor of the measured chase-alignment band (2.4.7 strafe fix): cos 45°.
     * A combo attacker steers with the crosshair ON the victim, so the deepest
     * sustained stance their velocity can hold off the knock axis is the full
     * forward-strafe (W+A / W+D) — moveFlying normalizes the (0.98, 0.98) diagonal
     * input to the pure-W impulse magnitude and rotates it 45° off facing
     * (dynamic-chase-movement-constants §3a). Below this the attacker is not
     * meaningfully chasing (an orbit or a backpedal) and the combo's own end rules
     * are the honest authority, so a noisier / lower measured dot clamps here
     * instead of over-shrinking the priced chase into a strafe-flavoured 2.4.6
     * undershoot.
     */
    public static final double MIN_CHASE_ALIGNMENT = Math.sqrt(0.5);

    /**
     * The least attacker net displacement (blocks, over the sampled heading span)
     * that counts as a measurable heading. Below it the direction is standing
     * jitter, not movement — the alignment degrades to {@link Double#NaN} (⇒ the
     * aligned 1.0 model, byte-identical to the pre-round solve).
     */
    public static final double MIN_HEADING_BLOCKS = 0.05;

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

    /**
     * The per-combo cadence EMA weight α (servo-lab 2.4.5, the gap-aware window).
     * The lab's within-cell cadence jitter is ±2 ticks (gap histograms 12–16 and
     * 17–19); an EMA reduces white-jitter variance by {@code α/(2−α)} ≈ 0.176, so
     * the smoothed cadence carries ≈ 0.6 ticks of noise — the ROUNDED window is
     * stable against jitter and moves only on a sustained rhythm change (≈ 3
     * windows to converge, 0.7³ ≈ 0.34 residual). Shared by the tracker's
     * grounded-run scaling and the predictor's window cadence — one rhythm model.
     */
    public static final double CADENCE_EMA_ALPHA = 0.3;

    /**
     * The hard ceiling on a measured cadence window (ticks): 3× the default
     * max-gap that ends every default combo — purely a robustness/allocation bound
     * on the fold (a garbage EMA can never size a giant schedule), NOT a knob. The
     * gap sources are themselves structurally bounded (the tracker rejects gaps
     * past {@code maxGapTicks}; the predictor memory rejects windows past 40t).
     */
    private static final double CADENCE_CEILING = 60.0;

    /** Vanilla player height (head-top above feet) — the attacker's answer geometry (§3.1). */
    public static final double PLAYER_HEIGHT = 1.8;

    /** Vanilla standing eye height above feet — the shared eye→AABB reach basis (§3.1). */
    public static final double EYE_HEIGHT = 1.62;

    /** The AABB half-width the feet-to-feet separation folds in (§3.1, w0 = 0.3). */
    public static final double HALF_WIDTH = 0.3;

    /* ── the per-combo chase EMA (servo-lab 2.4.5, the load-bearing chase channel) ─ */

    /**
     * The per-combo chase EMA weight α (≈ 0.3): the σ* placement solve prices the
     * smoothed POST-hit chase window (see {@link #windowChaseRate}), so the noisy
     * per-window estimate no longer coin-flips the shipped σ.
     */
    public static final double CHASE_EMA_ALPHA = 0.3;

    /* ── the retaliation-budget geometry (§3.1/§4; the t* that sizes the target) ─ */

    /** Below this |yawVsAxis| the victim needs no turn to answer. */
    private static final double TURN_FREE_YAW = 60.0;

    /**
     * The conservative yaw-slew divisor floor (deg/tick): a slow or unknown turner
     * is bounded here (a faster measured flick divides by its own, larger rate → a
     * smaller turn cost), so {@code turn} is honestly boundable.
     */
    private static final double TURN_RATE_FLOOR = 30.0;

    /** Real-valued victim ping ticks per ms of RTT: {@code tPing = rtt/100 = (rtt/2)/50}, NO floor. */
    private static final double PING_TICKS_PER_MS = 1.0 / 100.0;

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
     * double, double, double)} overload. Steers to {@code staticTarget} (the v1
     * form has no facing geometry).
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
                (config.staticTarget() - prediction.constant() - slope * residualCarry) / (freshEra * slope);
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
     * {@code launchGrounded} is the EFFECTIVE launch state the solve priced;
     * {@code launchRepriced} marks the touchdown-aware branch having promoted a
     * descending boundary capture to a grounded launch (servo-lab 2.4.5).
     *
     * <p>The target geometry (2.4.5): {@code target} is the applied steered
     * separation; {@code staticTarget} the config fallback; {@code sepDeny}/{@code
     * sepReach} the victim's deny boundary and the attacker's reach cap at {@code
     * arcHeightTStar} (the victim's feet height at the retaliation tick {@code t* =
     * tAllow = tPing + turn}). A declined solve reports the geometry as {@code 0}
     * (not evaluated).</p>
     */
    public record Solution(
            double sigma, double sigmaStar,
            double target, double staticTarget, double sepDeny, double sepReach, double arcHeightTStar,
            int windowTicks, int horizonTicks, int shiftVictimTicks, int airTicks, int landTick,
            double dragSum, double driftTravel, double chaseTravel, double chaseRate, boolean chaseMeasured,
            double d0, double residualCarry, double freshEra, double verticalStamp,
            boolean launchGrounded, double launchSlip, boolean declined,
            double predictedDNext,
            double tAllow, double turn,
            boolean launchRepriced) {
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
     * self-drift, ground-tail locomotion, ping horizons and the answer-denial
     * boundary target into the affine prediction, then inverts it for σ (§3.2b;
     * precision-derivation §8). Every degenerate case (inactive, no fresh lever,
     * air-time too brief, ice landing, collapsed window) declines to σ = 1.
     */
    public static Solution solve(
            PocketServoConfig config, PredictorInputs inputs,
            double d0, double residualCarry, double freshEra,
            double verticalStampShipped, double attackerNormalizedSpeed) {
        if (!config.active() || !(freshEra > EPSILON)) {
            return declined(config, d0, residualCarry, freshEra, verticalStampShipped, inputs);
        }
        Fold fold = simulate(config, inputs, d0, residualCarry, freshEra,
                verticalStampShipped, attackerNormalizedSpeed);
        if (fold.declined || !(fold.dragSum > EPSILON)) {
            return declined(config, d0, residualCarry, freshEra, verticalStampShipped, inputs);
        }
        double slope = fold.dragSum;
        double sigmaStar = (fold.target - fold.constant - slope * residualCarry) / (freshEra * slope);
        double blended = 1.0 + config.gain() * (sigmaStar - 1.0);
        double sigma = Math.max(config.min(), Math.min(config.max(), blended));
        double predictedDNext = fold.constant + slope * (residualCarry + sigma * freshEra);
        return new Solution(
                sigma, sigmaStar,
                fold.target, fold.staticTarget, fold.sepDeny, fold.sepReach, fold.arcHeightTStar,
                fold.wPrime, fold.tStar, fold.shiftV, fold.airTime, fold.tLand,
                fold.dragSum, fold.driftTravel, fold.chaseTravel, fold.chaseRate, fold.chaseMeasured,
                d0, residualCarry, freshEra, verticalStampShipped,
                fold.launchGrounded, inputs.launchSlip(), false, predictedDNext,
                fold.tAllow, fold.turn, fold.launchRepriced);
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
            double target, double staticTarget, double sepDeny, double sepReach, double arcHeightTStar,
            double constant, double tAllow, double turn,
            boolean launchGrounded, boolean launchRepriced) {
    }

    private static Fold simulate(
            PocketServoConfig config, PredictorInputs inputs,
            double d0, double residualCarry, double freshEra,
            double verticalStampShipped, double attackerNormalizedSpeed) {

        // The touchdown-aware launch branch (servo-lab 2.4.5). The era ordering
        // reads the END-of-previous-tick view, so a descending boundary hit
        // captures the victim airborne — but the victim's client moves by its
        // current vy BEFORE the stamp can apply, so when the remaining height is
        // below that one fall tick (launchHeight + vy ≤ 0) the actual flight is a
        // GROUNDED ground-level launch. Pricing it airborne is the lab's measured
        // 40%-class travel overestimate (grounded 1.162 measured = 1.162 modeled
        // vs the airborne-modeled 1.764 at the same shipped stamp) — the false-low
        // σ* mode (0.33–0.73) that pinned the min clamp. Only the flight PRICING
        // is repriced; the shipped stamp (air multipliers, vertical) is the era
        // composition and stays exactly what the engine extracted.
        boolean launchRepriced = !inputs.launchGrounded()
                && !Double.isNaN(inputs.launchVerticalVelocity())
                && inputs.launchVerticalVelocity() < 0.0
                && inputs.launchHeight() + inputs.launchVerticalVelocity() <= 0.0;
        if (launchRepriced) {
            inputs = inputs.asGroundedLaunch();
        }

        double staticTarget = config.staticTarget();
        // The gap-aware window (servo-lab 2.4.5): the cadence horizon follows the
        // attacker's MEASURED rhythm (the per-combo inter-hit gap EMA) instead of
        // the fixed config cadence. The lab's 12–19-tick attackers were priced over
        // a 10-tick window: the settle prediction stopped short of the swing that
        // actually judges the pocket (gap drift up to −1.3 blocks at 17–19t), and
        // the whole post-touchdown tail — the knock's ground drag AND the victim's
        // attribute-rate ground return — sat outside the priced window. An
        // unmeasured cadence (NaN / < 1) keeps the config windowTicks, so every
        // pre-round caller and pin is byte-identical.
        int cadence = config.windowTicks();
        if (!Double.isNaN(inputs.cadenceEmaTicks()) && inputs.cadenceEmaTicks() >= 1.0) {
            cadence = (int) Math.round(Math.min(inputs.cadenceEmaTicks(), CADENCE_CEILING));
        }
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
                    shiftV + airTime, 0.0, 0.0, 0.0, 0.0, false,
                    staticTarget, staticTarget, 0.0, 0.0, 0.0,
                    d0, 0.0, 0.0,
                    inputs.launchGrounded(), launchRepriced);
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
        for (int k = 1; k <= wPrime; k++) {
            dragSum += pi;
            boolean groundedPre = (k == 1) ? inputs.launchGrounded() : (k > tLand);
            double drag = groundedPre ? (k == 1 ? sqLaunch : sqLand) : Q;
            pi *= drag;
        }

        // (2) Victim self-drift (§2.5/§8): the estimated held input, axis-projected,
        // accumulated as the air double-sum S2 (rebuilding from 0 — the stamp wiped
        // the drift VELOCITY, not the held input). Only over the AIRBORNE ticks:
        // §2.5's own rule is that grounded ticks inside the window use the ground
        // constants INSTEAD (the §5 D_ground tail below), so the air sum truncates
        // at touchdown. Under the fixed 10-tick window this was moot (flights fill
        // the window); the gap-aware window makes the split load-bearing — an
        // 18-tick window over a 10-tick flight prices 8 ticks of 0.127-class
        // ground return, not 8 more ticks of 0.02-class air steering.
        double driftTravel = inputs.driftAlongAxis() * s2(Math.min(wPrime, tLand));

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

        // (4) Chase — the input-driven dynamic chase (ramp-aware, over the ACTUAL
        // window) when the reset model is present, else the measured attacker-velocity
        // trend, else the 0.2806×attr model (spec 2026-07-07; the fallback ladder).
        double chaseRate;
        double chaseTravel;
        boolean chaseMeasured;
        if (!Double.isNaN(inputs.chaseRampFactor()) && !Double.isNaN(inputs.chaseSteadySpeed())) {
            // The attacker's ramped re-accel out of their sprint reset, projected over
            // the flight window from the observed reset phase.
            chaseTravel = DynamicChase.projectTravel(
                    inputs.chaseSteadySpeed(), inputs.resetPhaseTicks(), inputs.chaseRampFactor(), wPrime);
            chaseRate = wPrime > 0 ? chaseTravel / wPrime : 0.0;
            chaseMeasured = true;
        } else if (!Double.isNaN(inputs.chaseAlongAxis())) {
            chaseRate = inputs.chaseAlongAxis();
            chaseTravel = chaseRate * wPrime;
            chaseMeasured = true;
        } else {
            chaseRate = SPRINT_GROUND_SPEED * chaseFactor(attackerNormalizedSpeed);
            chaseTravel = chaseRate * wPrime;
            chaseMeasured = false;
        }

        // (4b) The chase-alignment floor (2.4.6 undershoot fix). A real combo
        // attacker's straight-line effective close is a FRACTION of full sprint
        // (imperfect alignment + the per-hit w-tap dip), but the MEASURED window
        // averages the attacker's NET displacement over the whole inter-hit gap and
        // under-reads the harder post-knock chase burst the solve actually prices —
        // driving σ* into the false-low min-clamp (the measured 1.4-block window
        // solves σ*≈0.36–0.86 where the victim needs ≈1.0). Floor the chase at the
        // aligned-sprint rate for an ACTIVE combo (the attacker is closing by
        // definition); a genuinely harder measured chase still exceeds the floor and
        // wins. This is the single lever that lands σ in the calibrated band.
        double alignedFloorTravel =
                CHASE_ALIGNMENT * SPRINT_GROUND_SPEED * chaseFactor(attackerNormalizedSpeed) * wPrime;
        if (chaseTravel < alignedFloorTravel) {
            chaseTravel = alignedFloorTravel;
            chaseRate = wPrime > 0 ? chaseTravel / wPrime : 0.0;
            chaseMeasured = false;
        }

        // (5) The answer-denial-boundary target (§3.2b; the 2.4.5 redesign) — or the
        // static fallback. t* = tAllow = tPing + turn is the tick the victim could
        // FIRST retaliate; the geometry is read at the victim's feet height on the
        // arc THERE. victimReach is the EFFECTIVE (handicap-folded) reach the caller
        // supplied. No facing (NaN yaw) ⇒ the geometry is unmeasurable ⇒ the static
        // fallback; TargetMode.STATIC pins it there unconditionally.
        double turn = turn(inputs.victimYawVsAxisDeg(), inputs.victimYawRateDegPerTick());
        double tAllow = tAllow(inputs.victimRttMillis(),
                inputs.victimYawVsAxisDeg(), inputs.victimYawRateDegPerTick());
        int tStarArc = Math.max(0, (int) Math.round(tAllow));
        double arcH = arcHeight(verticalStampShipped, inputs.launchHeight(), tStarArc);
        double eyeV = Double.isNaN(inputs.victimEyeHeight()) ? EYE_HEIGHT : inputs.victimEyeHeight();
        double sepDeny = sepDeny(arcH, config.victimReach(), eyeV);
        double sepReach = sepReach(arcH, config.attackerReach());
        boolean boundaryMeasurable = config.targetMode() == TargetMode.BOUNDARY
                && !Double.isNaN(inputs.victimYawVsAxisDeg());
        double target = boundaryMeasurable
                ? boundaryTarget(arcH, config.victimReach(), config.attackerReach(), eyeV,
                        config.denyMargin(), config.jitterMargin(), config.targetFloor())
                : staticTarget;

        // constant = d0 − chase + drift + ground tail (all σ-independent); slope = Σ Π.
        double constant = d0 - chaseTravel + driftTravel + tail;
        return new Fold(false, wPrime, tStar, shiftV, airTime, tLand,
                dragSum, driftTravel + tail, chaseRate, chaseTravel, chaseMeasured,
                target, staticTarget, sepDeny, sepReach, arcH,
                constant, tAllow, turn,
                inputs.launchGrounded(), launchRepriced);
    }

    /* ── the answer-denial-boundary target geometry (§3.1/§3.2b) ──────────── */

    /**
     * The victim's DENY boundary at feet height {@code feetHeight} above the
     * attacker's ground (§3.1; the 2.4.5 answer-denial geometry): the maximum
     * feet-to-feet separation at which the victim, with effective eye→AABB reach
     * {@code victimReach}, can still land a hit back on the grounded attacker. Past
     * it the victim's answer is denied. Uses the vanilla standing eye height.
     */
    public static double sepDeny(double feetHeight, double victimReach) {
        return sepDeny(feetHeight, victimReach, EYE_HEIGHT);
    }

    /** {@link #sepDeny(double, double)} with a pose-aware victim eye height. */
    public static double sepDeny(double feetHeight, double victimReach, double victimEye) {
        // The victim's eye above the attacker's head-top (the nearest point of the
        // attacker's [0, 1.8] box the victim aims down at); 0 while level or below.
        double dvertV = Math.max(0.0, feetHeight + victimEye - PLAYER_HEIGHT);
        if (dvertV >= victimReach) {
            return Double.POSITIVE_INFINITY; // too high to ever answer — no deny boundary
        }
        return HALF_WIDTH + Math.sqrt(victimReach * victimReach - dvertV * dvertV);
    }

    /**
     * The attacker's REACH cap at victim feet height {@code feetHeight} (§3.1): the
     * maximum feet-to-feet separation at which the grounded attacker, with eye→AABB
     * reach {@code attackerReach}, can still land a hit on the elevated victim. The
     * attacker's eye is the vanilla standing 1.62; {@code dvertA} is the eye's
     * distance to the nearest point of the victim's [h, h+1.8] box (usually
     * {@code max(0, h − 1.62)}).
     */
    public static double sepReach(double feetHeight, double attackerReach) {
        double dvertA = Math.max(0.0,
                Math.max(feetHeight - EYE_HEIGHT, EYE_HEIGHT - (feetHeight + PLAYER_HEIGHT)));
        if (dvertA >= attackerReach) {
            return HALF_WIDTH; // the attacker cannot reach at this height — the degenerate minimum
        }
        return HALF_WIDTH + Math.sqrt(attackerReach * attackerReach - dvertA * dvertA);
    }

    /**
     * The answer-denial-boundary target at victim feet height {@code feetHeight}
     * (§3.2b; the 2.4.5 redesign), using the vanilla standing eye.
     */
    public static double boundaryTarget(
            double feetHeight, double victimReach, double attackerReach,
            double denyMargin, double jitterMargin, double targetFloor) {
        return boundaryTarget(feetHeight, victimReach, attackerReach, EYE_HEIGHT,
                denyMargin, jitterMargin, targetFloor);
    }

    /**
     * The answer-denial-boundary target (pose-aware victim eye). Aims just past the
     * victim's deny boundary ({@code sepDeny + denyMargin}, so jitter can't let them
     * answer) but never past the attacker's own reach ({@code sepReach −
     * jitterMargin}, so the attacker reliably connects), and never pulls IN below
     * {@code targetFloor}:
     * {@code min(sepReach − jitter, max(targetFloor, sepDeny + deny))}. When the
     * victim can never answer at this height (the deny boundary collapses) the deny
     * term vanishes and the target clamps to reachability — keep them hittable so
     * the combo continues (denial is the reach-handicap/vertical submodule's job
     * there).
     */
    public static double boundaryTarget(
            double feetHeight, double victimReach, double attackerReach, double victimEye,
            double denyMargin, double jitterMargin, double targetFloor) {
        double sepDeny = sepDeny(feetHeight, victimReach, victimEye);
        double denyBound = Double.isInfinite(sepDeny)
                ? Double.POSITIVE_INFINITY                  // no deny — favour reachability
                : Math.max(targetFloor, sepDeny + denyMargin);
        double reachBound = sepReach(feetHeight, attackerReach) - jitterMargin;
        return Math.min(reachBound, denyBound);
    }

    /**
     * The retaliation turn cost in real-valued ticks: {@code 0} within
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
     * The victim's earliest-retaliation budget in real-valued ticks (the target's
     * {@code t*}): {@code tPing + turn}, {@code tPing = rttV/100} (== half-RTT in
     * ticks, NO floor). An unavailable RTT ({@code < 0}) contributes no ping slack.
     */
    public static double tAllow(double victimRttMillis, double yawVsAxisDeg, double yawRateDegPerTick) {
        double tPing = victimRttMillis >= 0.0 ? victimRttMillis * PING_TICKS_PER_MS : 0.0;
        return tPing + turn(yawVsAxisDeg, yawRateDegPerTick);
    }

    /** The per-combo chase EMA advance (servo-lab 2.4.5): a NaN prior seeds to the instantaneous rate. */
    public static double chaseEma(double priorChaseEma, double chaseRate) {
        return Double.isNaN(priorChaseEma)
                ? chaseRate
                : CHASE_EMA_ALPHA * chaseRate + (1.0 - CHASE_EMA_ALPHA) * priorChaseEma;
    }

    /**
     * The per-combo cadence EMA advance (servo-lab 2.4.5): a NaN prior seeds to
     * the first observed gap, then the {@link #CADENCE_EMA_ALPHA} blend. One
     * rhythm model for both consumers — the solve's gap-aware window and the
     * tracker's grounded-run scaling.
     */
    public static double cadenceEma(double prior, double gapTicks) {
        return Double.isNaN(prior)
                ? gapTicks
                : CADENCE_EMA_ALPHA * gapTicks + (1.0 - CADENCE_EMA_ALPHA) * prior;
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

    /**
     * The attacker's movement-heading alignment with the knock axis (2.4.7 strafe
     * fix): the dot of the NORMALIZED horizontal heading {@code (headingX,
     * headingZ)} — the attacker's net displacement over the last few ticks — with
     * the attacker→victim unit axis {@code (ux, uz)}. +1 = chasing straight down
     * the axis; ≈ +0.7071 = a full forward-strafe (W+A / W+D); ≤ 0 = orbiting or
     * backpedaling. Returned RAW (unclamped) so consumers can observe the true
     * stance; the solve clamps through {@link #chaseAlignmentFactor}. A heading
     * shorter than {@link #MIN_HEADING_BLOCKS} is standing jitter — no measurable
     * direction — and returns {@link Double#NaN} without dividing.
     */
    public static double headingAlignment(double headingX, double headingZ, double ux, double uz) {
        double magnitude = Math.sqrt(headingX * headingX + headingZ * headingZ);
        if (!(magnitude >= MIN_HEADING_BLOCKS)) {
            return Double.NaN;
        }
        return (headingX * ux + headingZ * uz) / magnitude;
    }

    /**
     * The chase-alignment factor the MODEL channels price (2.4.7 strafe fix):
     * {@link Double#NaN} (no signal) resolves to the aligned 1.0 — byte-identical
     * to every pre-round solve — and a measured dot clamps into
     * {@code [MIN_CHASE_ALIGNMENT, 1.0]}. The low clamp is deliberate: below
     * cos 45° the attacker is not in a crosshair-on-victim chase stance and the
     * combo's own end rules (gap / blowout / retaliation) own the outcome — the
     * servo must not price a near-zero or NEGATIVE chase off one noisy heading (a
     * w-tap instant, or the attacker's own received knock), which would over-shrink
     * σ and resurrect the 2.4.6 undershoot in strafe form.
     */
    public static double chaseAlignmentFactor(double alignment) {
        if (Double.isNaN(alignment)) {
            return 1.0;
        }
        return Math.max(MIN_CHASE_ALIGNMENT, Math.min(1.0, alignment));
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
     * clamped at the ground once landed — the boundary target's Δ(t) source.
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
            double verticalStamp, PredictorInputs inputs) {
        double staticTarget = config.staticTarget();
        return new Solution(
                1.0, 1.0,
                staticTarget, staticTarget, 0.0, 0.0, 0.0,
                0, 0, 0, 0, 0,
                0.0, 0.0, 0.0, 0.0, false,
                d0, residualCarry, freshEra, verticalStamp,
                inputs.launchGrounded(), inputs.launchSlip(), true, d0,
                0.0, 0.0, false);
    }
}
