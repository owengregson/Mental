package me.vexmc.mental.kernel.math;

/**
 * The predictor's output (combo-hold §3.2/§3.2b) — the affine map from the
 * victim's shipped horizontal speed to the attacker→victim separation at the
 * next swing, plus the separation the solve steers toward. This is the <b>one
 * seam the precision round extends</b>: the v1 predictor ({@link
 * PocketServo#predict}) fills {@code constant = d0 − chase} and {@code slope =
 * dragSum}; the precision predictor folds victim self-drift, the launch-state drag
 * branch, ground-tail travel, ping horizons, and the exposure-budget dynamic
 * target into those same numbers — the {@link PocketServo#sigma} inversion
 * consumes the affine form and never needs to know how it was built.
 *
 * <p>The map is affine because the servo scales only the FRESH knock: the
 * shipped horizontal is {@code residualCarry + σ·freshEra}, and separation is
 * linear in it. Keeping the boundary affine is what lets the solve invert in
 * closed form (no root-finding), so the exact-inverse property survives every
 * predictor upgrade that stays affine in the shipped speed.</p>
 *
 * @param constant    separation at zero shipped horizontal (d0 minus the
 *                    attacker's closing, plus every velocity-independent drift and
 *                    ground-tail term).
 * @param slope       separation gained per unit of shipped horizontal speed (the
 *                    launch-branch drag schedule Σ Π over the flight window).
 * @param windowTicks the flight window the prediction used (the ping-shifted
 *                    horizon), exposed for pins and the debug sink.
 * @param target      the separation the solve steers toward — the config anchor,
 *                    or the resolved exposure-budget dynamic target (§3.3). A
 *                    declined prediction ({@code slope == 0}) carries the anchor.
 */
public record FlightPrediction(double constant, double slope, int windowTicks, double target) {

    /**
     * The v1 arity — no explicit target (the {@link PocketServo#sigma} v1 solve
     * reads {@code config.target()} directly, so the carried value is inert there;
     * a {@link Double#NaN} makes an accidental read fail loudly rather than silently
     * substitute a wrong anchor).
     */
    public FlightPrediction(double constant, double slope, int windowTicks) {
        this(constant, slope, windowTicks, Double.NaN);
    }

    /** The predicted separation at next-swing time for a given total shipped horizontal speed. */
    public double separationAfter(double shippedHorizontal) {
        return constant + slope * shippedHorizontal;
    }
}
