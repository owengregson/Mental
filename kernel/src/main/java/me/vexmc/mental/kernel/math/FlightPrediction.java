package me.vexmc.mental.kernel.math;

/**
 * The predictor's output (combo-hold §3.2/§3.2b) — the affine map from the
 * victim's shipped horizontal speed to the attacker→victim separation at the
 * next swing. This is the <b>one seam the precision round extends</b>: the v1
 * predictor ({@link PocketServo#predict}) fills {@code constant = d0 − chase}
 * and {@code slope = dragSum}; a later predictor may fold in victim self-drift,
 * axis projection, or a ping horizon by changing only those two numbers — the
 * {@link PocketServo#sigma} solve consumes the affine form and never needs to
 * know how it was built.
 *
 * <p>The map is affine because the servo scales only the FRESH knock: the
 * shipped horizontal is {@code residualCarry + σ·freshEra}, and separation is
 * linear in it. Keeping the boundary affine is what lets the solve invert in
 * closed form (no root-finding), so the exact-inverse property survives every
 * predictor upgrade that stays affine in the shipped speed.</p>
 *
 * @param constant    separation at zero shipped horizontal (d0 minus the
 *                    attacker's closing, plus any v-independent drift terms).
 * @param slope       separation gained per unit of shipped horizontal speed
 *                    (the era air-drag geometric sum over the flight window).
 * @param windowTicks the truncated flight window the prediction used (≤ config
 *                    window; cut at touchdown), exposed for pins and journaling.
 */
public record FlightPrediction(double constant, double slope, int windowTicks) {

    /** The predicted separation at next-swing time for a given total shipped horizontal speed. */
    public double separationAfter(double shippedHorizontal) {
        return constant + slope * shippedHorizontal;
    }
}
