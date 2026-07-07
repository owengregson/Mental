package me.vexmc.mental.kernel.math;

/**
 * The precision-round predictor inputs (combo-hold §3.2b; precision-derivation
 * §0/§1–§5) — every per-hit quantity the {@link PocketServo} solve needs BEYOND
 * the four v1 inputs ({@code d0}, {@code residualCarry}, the shipped vertical
 * stamp, the attacker's normalized speed). One immutable value both compute sites
 * (netty pre-send D1, region compute D2) freeze and pass into the engine's servo
 * overload; the engine grows no {@link me.vexmc.mental.kernel.model.EntityState}
 * arity (the seams doc's zero-EntityState-growth rule), so this record is the
 * whole precision seam.
 *
 * <p>Every directional quantity is a SCALAR already projected on the
 * attacker→victim axis {@code u} (pointing away from the attacker) by the caller
 * — the axis-projection-everywhere rule. {@code driftAlongAxis} is positive when
 * the victim's held input flees the attacker; {@code chaseAlongAxis} is positive
 * when the attacker closes. This keeps the kernel solve axis-blind: it never
 * re-derives the geometry the caller already has.</p>
 *
 * <p>All fields degrade individually — a missing input never breaks the solve, it
 * only drops its own term (precision-derivation §6 residual budget). The
 * {@link #degraded(boolean, double, double)} factory is the base-correctness path:
 * the mandatory launch-ground-state branch (row 1) with every precision extra
 * off, so the v1 grounded/air solve stands.</p>
 *
 * @param launchGrounded          the victim's pre-move ground state at launch —
 *                                selects the mandatory grounded-vs-air drag branch
 *                                (§1.2). The one flag the whole D-schedule turns on.
 * @param launchSlip              the block-under-feet slipperiness at launch
 *                                ({@code sq = slip × 0.91}); default 0.6 stone.
 * @param landingSlip             the predicted landing-segment slipperiness — the
 *                                servo DECLINES (σ = 1) when it exceeds 0.7 (era ice
 *                                compounding changes the pocket, §6 issue 6). Flat
 *                                arenas: equal to {@code launchSlip}.
 * @param launchHeight            the victim's feet height above ground at launch
 *                                (0 for a grounded launch); shifts the air-time sim.
 * @param driftAlongAxis          the estimated per-tick victim input acceleration
 *                                on the axis (b/t², |·| ≤ 0.026), + = fleeing (§2.4).
 * @param chaseAlongAxis          the MEASURED attacker closing rate on the axis
 *                                (b/t), + = closing; {@link Double#NaN} ⇒ the
 *                                0.2806×attr/0.10 attribute model is the fallback (§1.4).
 * @param victimNormalizedSpeed   the victim's OWN walk-normalized move-speed attr
 *                                — the ground-tail locomotion rate (§5); ≤ 0 ⇒ walk
 *                                baseline (factor 1.0).
 * @param attackerRttMillis       the attacker's full round-trip time (ms); &lt; 0 ⇒
 *                                unavailable ⇒ horizon shift 0 (§4).
 * @param victimRttMillis         the victim's full round-trip time (ms); &lt; 0 ⇒
 *                                unavailable ⇒ flight-shift / retaliation slack 0 (§4).
 * @param attackerHeadY           the attacker's head-top world Y (pose-aware) for
 *                                the dynamic-target triangle; {@link Double#NaN} ⇒
 *                                the flat-arena head constant.
 * @param victimEyeHeight         the victim's eye height above feet (pose-aware);
 *                                {@link Double#NaN} ⇒ the 1.62 standing constant.
 * @param victimYawVsAxisDeg      the victim's facing offset from the axis-to-attacker,
 *                                in [0, 180]; {@link Double#NaN} ⇒ the dynamic target
 *                                falls back to the anchor.
 * @param victimYawRateDegPerTick the victim's recent measured yaw slew rate
 *                                (deg/tick, mean |Δyaw| over the last ~3 ticks) — the
 *                                V2 continuous {@code turn} term's divisor; a faster
 *                                flick earns a smaller cost. {@link Double#NaN} ⇒ the
 *                                conservative 30°/tick floor (target-v2 repair #4).
 * @param groundedTicks           consecutive grounded ticks the victim has held
 *                                (published context; carried for the debug sink and
 *                                launch-state coherence, never a solve lever on its own).
 * @param priorChaseEma           <b>Unused since 2.4.5</b> — fed the removed
 *                                exposure-budget dynamic-target smoothing; retained
 *                                so the predictor-input arity and every caller/pin
 *                                stay stable across the answer-denial-boundary
 *                                redesign (the boundary target needs no cross-hit
 *                                prior). Pass {@link Double#NaN}.
 * @param priorDynamicTarget      <b>Unused since 2.4.5</b> — fed the removed
 *                                dynamic-target slew limit; retained for arity
 *                                stability. Pass {@link Double#NaN}.
 * @param launchVerticalVelocity  the victim's per-tick vertical motion at the
 *                                boundary read (the same end-of-previous-tick view
 *                                the residual rides). The touchdown-aware launch
 *                                branch (servo-lab 2.4.5): a descending victim whose
 *                                remaining height is below one fall tick grounds on
 *                                the client BEFORE the stamp applies, so the solve
 *                                reprices the flight as a GROUNDED ground-level
 *                                launch. {@link Double#NaN} ⇒ no repricing (the
 *                                pre-2.4.5 behaviour, byte-identical).
 * @param cadenceEmaTicks         the per-combo EMA of observed inter-hit gaps
 *                                (servo-lab 2.4.5) — the gap-aware window: the
 *                                solve's cadence horizon follows the attacker's
 *                                MEASURED rhythm instead of the fixed config
 *                                cadence, so a 12–19-tick attacker's settle is
 *                                predicted over the window that actually elapses
 *                                (fixed-10 pricing stopped seven-plus ticks short
 *                                and hid the whole post-touchdown tail).
 *                                {@link Double#NaN} or &lt; 1 ⇒ the config
 *                                {@code windowTicks} (the era 10), byte-identical.
 */
public record PredictorInputs(
        boolean launchGrounded,
        double launchSlip,
        double landingSlip,
        double launchHeight,
        double driftAlongAxis,
        double chaseAlongAxis,
        double victimNormalizedSpeed,
        int attackerRttMillis,
        int victimRttMillis,
        double attackerHeadY,
        double victimEyeHeight,
        double victimYawVsAxisDeg,
        double victimYawRateDegPerTick,
        int groundedTicks,
        double priorChaseEma,
        double priorDynamicTarget,
        double launchVerticalVelocity,
        double cadenceEmaTicks,
        // The input-driven dynamic chase (spec 2026-07-07): the attacker's ramp-asymptote
        // closing speed (b/t; NaN ⇒ no model), ticks since their sprint reset (the ramp
        // phase), and the per-tick re-accel ramp factor r ∈ (0,1) (NaN ⇒ no model → the
        // measured-ring / attribute chase). Both NaN sentinels gate the dynamic branch off.
        double chaseSteadySpeed,
        int resetPhaseTicks,
        double chaseRampFactor) {

    /**
     * The pre-dynamic-chase arity (2026-07-07 servo dynamic chase): every input through
     * the gap-aware cadence, with NO input-driven chase model — {@code chaseSteadySpeed}
     * and {@code chaseRampFactor} default to {@link Double#NaN}, so the servo falls back
     * to the measured-ring / attribute chase. Keeps every caller that predates the
     * dynamic chase compiling and solving unchanged.
     */
    public PredictorInputs(
            boolean launchGrounded, double launchSlip, double landingSlip, double launchHeight,
            double driftAlongAxis, double chaseAlongAxis, double victimNormalizedSpeed,
            int attackerRttMillis, int victimRttMillis, double attackerHeadY, double victimEyeHeight,
            double victimYawVsAxisDeg, double victimYawRateDegPerTick, int groundedTicks,
            double priorChaseEma, double priorDynamicTarget, double launchVerticalVelocity,
            double cadenceEmaTicks) {
        this(launchGrounded, launchSlip, landingSlip, launchHeight, driftAlongAxis, chaseAlongAxis,
                victimNormalizedSpeed, attackerRttMillis, victimRttMillis, attackerHeadY, victimEyeHeight,
                victimYawVsAxisDeg, victimYawRateDegPerTick, groundedTicks, priorChaseEma, priorDynamicTarget,
                launchVerticalVelocity, cadenceEmaTicks, Double.NaN, 0, Double.NaN);
    }

    /**
     * The touchdown-branch arity (servo-solve round, step two): the boundary-read
     * vertical motion but no measured cadence — the window stays the config
     * {@code windowTicks}.
     */
    public PredictorInputs(
            boolean launchGrounded, double launchSlip, double landingSlip, double launchHeight,
            double driftAlongAxis, double chaseAlongAxis, double victimNormalizedSpeed,
            int attackerRttMillis, int victimRttMillis, double attackerHeadY, double victimEyeHeight,
            double victimYawVsAxisDeg, double victimYawRateDegPerTick, int groundedTicks,
            double priorChaseEma, double priorDynamicTarget, double launchVerticalVelocity) {
        this(launchGrounded, launchSlip, landingSlip, launchHeight, driftAlongAxis, chaseAlongAxis,
                victimNormalizedSpeed, attackerRttMillis, victimRttMillis, attackerHeadY, victimEyeHeight,
                victimYawVsAxisDeg, victimYawRateDegPerTick, groundedTicks, priorChaseEma, priorDynamicTarget,
                launchVerticalVelocity, Double.NaN);
    }

    /**
     * The pre-2.4.5 arity (the target-v2 round): no boundary-read vertical motion
     * and no measured cadence, so the touchdown-aware launch branch never fires and
     * the window stays the config cadence. Keeps every caller and pin that predates
     * the servo-solve round compiling — and solving — unchanged.
     */
    public PredictorInputs(
            boolean launchGrounded, double launchSlip, double landingSlip, double launchHeight,
            double driftAlongAxis, double chaseAlongAxis, double victimNormalizedSpeed,
            int attackerRttMillis, int victimRttMillis, double attackerHeadY, double victimEyeHeight,
            double victimYawVsAxisDeg, double victimYawRateDegPerTick, int groundedTicks,
            double priorChaseEma, double priorDynamicTarget) {
        this(launchGrounded, launchSlip, landingSlip, launchHeight, driftAlongAxis, chaseAlongAxis,
                victimNormalizedSpeed, attackerRttMillis, victimRttMillis, attackerHeadY, victimEyeHeight,
                victimYawVsAxisDeg, victimYawRateDegPerTick, groundedTicks, priorChaseEma, priorDynamicTarget,
                Double.NaN, Double.NaN);
    }

    /**
     * The pre-V2 arity (combo-hold §3.2b): every per-hit geometry input, with NO
     * per-combo servo memory ({@code priorChaseEma}/{@code priorDynamicTarget}
     * default to {@link Double#NaN} ⇒ the V2 EMA seeds to the instantaneous chase
     * and the slew passes through — a first-hit / memoryless solve). Keeps every
     * caller and pin that predates the target-v2 round compiling unchanged.
     */
    public PredictorInputs(
            boolean launchGrounded, double launchSlip, double landingSlip, double launchHeight,
            double driftAlongAxis, double chaseAlongAxis, double victimNormalizedSpeed,
            int attackerRttMillis, int victimRttMillis, double attackerHeadY, double victimEyeHeight,
            double victimYawVsAxisDeg, double victimYawRateDegPerTick, int groundedTicks) {
        this(launchGrounded, launchSlip, landingSlip, launchHeight, driftAlongAxis, chaseAlongAxis,
                victimNormalizedSpeed, attackerRttMillis, victimRttMillis, attackerHeadY, victimEyeHeight,
                victimYawVsAxisDeg, victimYawRateDegPerTick, groundedTicks, Double.NaN, Double.NaN);
    }

    /**
     * This hit repriced as a grounded, ground-level launch — the touchdown-aware
     * launch branch's view (servo-lab 2.4.5). The launch slip doubles as the drag
     * under the victim's feet at the repriced launch (flat-arena equality with the
     * landing slip, the same convention the captured inputs already carry); the
     * vertical motion is spent by the touchdown, so it clears to {@link Double#NaN}.
     */
    public PredictorInputs asGroundedLaunch() {
        return new PredictorInputs(
                true, launchSlip, landingSlip, 0.0,
                driftAlongAxis, chaseAlongAxis, victimNormalizedSpeed,
                attackerRttMillis, victimRttMillis, attackerHeadY, victimEyeHeight,
                victimYawVsAxisDeg, victimYawRateDegPerTick, groundedTicks,
                priorChaseEma, priorDynamicTarget, Double.NaN, cadenceEmaTicks,
                chaseSteadySpeed, resetPhaseTicks, chaseRampFactor);
    }

    /**
     * This input set with the input-driven dynamic chase attached (spec 2026-07-07):
     * the attacker's ramp-asymptote {@code steadySpeed} (already alignment×technique
     * resolved), the {@code phaseTicks} since their sprint reset, and the re-accel
     * {@code rampFactor}. The servo then prices the chase via {@link DynamicChase} over
     * the real flight window instead of a flat rate. A NaN {@code steadySpeed}/{@code
     * rampFactor} leaves the dynamic branch off (the measured-ring / attribute fallback).
     */
    public PredictorInputs withDynamicChase(double steadySpeed, int phaseTicks, double rampFactor) {
        return new PredictorInputs(
                launchGrounded, launchSlip, landingSlip, launchHeight, driftAlongAxis, chaseAlongAxis,
                victimNormalizedSpeed, attackerRttMillis, victimRttMillis, attackerHeadY, victimEyeHeight,
                victimYawVsAxisDeg, victimYawRateDegPerTick, groundedTicks, priorChaseEma, priorDynamicTarget,
                launchVerticalVelocity, cadenceEmaTicks, steadySpeed, phaseTicks, rampFactor);
    }

    /**
     * The base-correctness inputs (precision-derivation §6 row 1): the mandatory
     * launch-ground-state branch and its slip, every precision extra off. Used by
     * the engine's pace-only-plus-servo overload and any caller without the
     * precision seam wired — the v1 grounded/air solve, no drift, no ping, no
     * dynamic target, the fallback chase model.
     *
     * @param launchGrounded        the victim's launch ground state (from the view/capture).
     * @param launchSlip            the launch-block slipperiness (0.6 stone default).
     * @param victimNormalizedSpeed the victim's own move-speed attr, or ≤ 0 for the baseline.
     */
    public static PredictorInputs degraded(
            boolean launchGrounded, double launchSlip, double victimNormalizedSpeed) {
        return new PredictorInputs(
                launchGrounded, launchSlip, launchSlip, 0.0,
                0.0, Double.NaN, victimNormalizedSpeed,
                -1, -1, Double.NaN, Double.NaN, Double.NaN, 0.0, 0);
    }
}
