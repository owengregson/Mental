package me.vexmc.mental.kernel.math;

/**
 * The pocket servo (combo-hold §3.2): the factor {@code σ} that scales the fresh
 * melee horizontal knock so the victim lands at a chosen separation from the
 * attacker — held in the un-retaliatable pocket, one swing from now. This is not
 * a proportional nudge; it is the <b>exact inverse solve of the era flight
 * equations</b> (owner directive 2026-07-04: the victim must land in a very
 * specific position), then clamped.
 *
 * <h2>The physics being inverted</h2>
 * <p>After a knock the victim carries {@code residualCarry + σ·freshEra} of
 * horizontal speed along the attacker→victim axis (the ledger residual is never
 * scaled — the A3 law), and each tick that speed advances the position and then
 * decays by the era air drag {@code 0.91}. Over a window of {@code w} ticks the
 * victim travels {@code v0 · dragSum}, where</p>
 * <pre>
 *   dragSum = Σ_{k=0}^{w-1} 0.91^k = (1 − 0.91^w) / (1 − 0.91)      [geometric sum]
 * </pre>
 * <p>(position after {@code w} ticks is {@code v0·(1 + 0.91 + … + 0.91^{w-1})},
 * because the entity moves by the current velocity before that tick's drag).
 * Meanwhile the sprinting attacker closes {@code chase = 0.2806 · (attr/0.10) · w}
 * blocks. So the separation at the next swing is</p>
 * <pre>
 *   dNext = d0 + (residualCarry + σ·freshEra)·dragSum − chase
 * </pre>
 * <p>and the servo picks the {@code σ*} that makes {@code dNext == target}:</p>
 * <pre>
 *   σ* = (target − d0 + chase − residualCarry·dragSum) / (freshEra · dragSum)
 *   σ  = clamp(min, max, 1 + gain·(σ* − 1))
 * </pre>
 * <p>Worked spot-check (base-speed sprint, w=10): dragSum = (1 − 0.91^10)/0.09 ≈
 * 6.78427, chase = 0.2806·1·10 = 2.806. With d0 = 3.0, residualCarry = 0, and a
 * standing fresh knock freshEra = 0.4: σ* = (2.75 − 3.0 + 2.806)/(0.4·6.78427) =
 * 2.556/2.713708 ≈ 0.9419 — a gentle pull-in, inside the clamps.</p>
 *
 * <h2>The window truncates at touchdown</h2>
 * <p>Horizontal carry effectively dies when the victim lands, so the flight
 * window is {@code min(windowTicks, airTime(verticalStamp))} — {@link #airTime}
 * is the kernel's own tick simulation of the shipped vertical stamp from ground
 * level (the {@code CompensationQuery} flight-sim precedent, gravity/drag from
 * the {@link Decay} constants). v1 assumes launch from ground level (combo hits
 * overwhelmingly connect in touchdown windows — the 1.6.0 boundary-ordering
 * work); the simulation grid pins quantify that approximation's error.</p>
 *
 * <h2>The predictor is one swappable seam</h2>
 * <p>Everything that turns flight geometry into "separation per unit shipped
 * speed" lives in {@link #predict}, which returns an affine {@link
 * FlightPrediction}. The precision round (§3.2b — victim self-drift, axis
 * projection, dynamic target, ping horizons, ground-tail drift) extends ONLY
 * that function; {@link #sigma} consumes the affine form and is untouched. v1 is
 * honestly the exact solve over four inputs: {@code d0}, {@code residualCarry},
 * the shipped vertical stamp, and the attacker's normalized speed.</p>
 */
public final class PocketServo {

    /**
     * Era sprint ground speed in blocks/tick — the attacker's closing rate at
     * base speed (vanilla 1.8 sprint ≈ 5.6 m/s = 0.2806 b/t). Scaled by the
     * attacker's walk-normalized movement-speed attribute over the 0.10 baseline,
     * so Speed III closes 1.6× as fast. Sprint is assumed: the combo holder is
     * chasing.
     */
    public static final double SPRINT_GROUND_SPEED = 0.2806;

    /** The walk-stance movement-speed baseline (0.1 player base) — the chase-factor denominator. */
    public static final double WALK_BASELINE = 0.10;

    /** Below this the fresh knock or the flight window is effectively nil; the servo declines (σ = 1). */
    private static final double EPSILON = 1.0e-9;

    /** A guard so a pathological vertical stamp can never spin the air-time sim forever. */
    private static final int MAX_AIR_TICKS = 1200;

    private PocketServo() {}

    /**
     * The clamped exact-inverse servo factor. Returns exactly {@code 1.0} when
     * the config is inactive, when there is no fresh horizontal to scale, or when
     * the flight window collapses to zero (immediate touchdown) — every path that
     * cannot honestly steer the victim leaves the era stamp untouched.
     *
     * @param config                   the servo tunables (INACTIVE ⇒ 1.0).
     * @param d0                       current attacker→victim horizontal separation.
     * @param residualCarry            the un-scaled friction-carried horizontal (A3).
     * @param freshEra                 the fresh horizontal knock magnitude at σ = 1.
     * @param attackerNormalizedSpeed  the attacker's walk-normalized move-speed attr.
     * @param verticalStampShipped     the vertical the knock ships (drives air time).
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
        // Invert the affine prediction: find σ such that
        //   prediction.separationAfter(residualCarry + σ·freshEra) == target.
        double sigmaStar =
                (config.target() - prediction.constant() - slope * residualCarry) / (freshEra * slope);
        double blended = 1.0 + config.gain() * (sigmaStar - 1.0);
        return Math.max(config.min(), Math.min(config.max(), blended));
    }

    /**
     * The v1 predictor — the seam the precision round extends (§3.2b). It maps a
     * unit of shipped horizontal speed to separation gained ({@code slope =
     * dragSum}) and computes the separation at zero shipped speed ({@code
     * constant = d0 − chase}). Pure; no config state beyond the passed values.
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

    /** {@code min(windowTicks, airTime(verticalStamp))} — the flight window cut at touchdown. */
    public static int effectiveWindow(int windowTicks, double verticalStampShipped) {
        return Math.min(windowTicks, airTime(verticalStampShipped));
    }

    /**
     * The number of ticks a launch of {@code verticalStamp} (from ground level)
     * stays airborne, by the kernel's own tick simulation: move by the current
     * vertical velocity, then apply gravity and the vertical drag, until the
     * entity returns to ground. A non-positive stamp is already grounded, so the
     * horizontal carry never gets an airborne window (0). v1 launch-from-ground
     * assumption; the grid pins bound its error.
     */
    public static int airTime(double verticalStamp) {
        if (!(verticalStamp > 0.0)) {
            return 0;
        }
        double y = 0.0;
        double vy = verticalStamp;
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

    /** The era air-drag geometric sum {@code Σ_{k=0}^{window-1} 0.91^k}; zero for an empty window. */
    public static double dragSum(int window) {
        if (window <= 0) {
            return 0.0;
        }
        return (1.0 - Math.pow(Decay.AIR_DRAG, window)) / (1.0 - Decay.AIR_DRAG);
    }

    /** The attacker's closing multiplier: normalized speed over the walk baseline (unavailable ⇒ 1.0). */
    public static double chaseFactor(double attackerNormalizedSpeed) {
        double attr = attackerNormalizedSpeed > 0.0 ? attackerNormalizedSpeed : WALK_BASELINE;
        return attr / WALK_BASELINE;
    }
}
