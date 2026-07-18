package me.vexmc.mental.kernel.math;

/**
 * Recovers the victim's held mid-air input from the wire, history-free
 * (combo-hold §3.2b; precision-derivation §2.4). The position ring is the only
 * source of a knocked victim's steering: held keys stack CLIENT-side and never
 * enter the server motion fields, so the server sees them only as the ring's
 * measured displacement exceeding the modeled knock decay.
 *
 * <p>Let {@code u_t} be the measured per-tick displacement on the attacker→victim
 * axis and {@code k_t} the modeled knock move for that tick (the ledger's decayed
 * stamp — the flight model the wire anchors validated). The input-driven residual
 * {@code e_t = u_t − k_t} obeys {@code e_t = q·e_{t-1} + a_t} airborne (air drag
 * {@code q = 0.91}, {@code a_t} = that tick's input Δv), so the per-tick input is
 * recovered EXACTLY, with no recursion:</p>
 *
 * <pre>
 *   â_t = e_t − q·e_{t-1} = (u_t − q·u_{t-1}) − (k_t − q·k_{t-1})
 * </pre>
 *
 * <p>The estimate is the mean of the last {@link #WINDOW} valid {@code â_t},
 * clamped to the hard physics bound — long enough to average packet-cadence
 * jitter, short enough to track a human key-flip on its 2–5-tick scale. A model
 * break (a missed ground transition, a sample-aliased packet burst) shows up as
 * {@code |â_t|} past the physics bound plus slack and is discarded; fewer than two
 * valid samples drops the term entirely (â = 0, the base solve stands). The caller
 * supplies clean runs — it excludes ticks straddling a ledger record / liftoff /
 * landing and ticks with no fresh ring sample (the seams doc's validity gates)
 * before projecting on the axis.</p>
 */
public final class DriftEstimator {

    /** The air-drag decay that the input residual obeys between ticks (§2.4). */
    private static final double Q = Decay.AIR_DRAG;

    /**
     * The hard per-tick input bound plus slack (§2.1: sprint-air {@code 0.026}
     * jumpMovementFactor). A recovered {@code â_t} past this signals a model break,
     * not real input, so it is discarded.
     */
    public static final double VALIDITY_BOUND = 0.03;

    /**
     * The estimate clamp — the sprint-air input magnitude with a hair of slack
     * ({@code 0.026 × 1.0154 ≈ 0.0264}, the §2.4 clamp), the ceiling no held key
     * can exceed.
     */
    public static final double CLAMP = 0.0264;

    /** The averaging window {@code N} (§2.4): three valid samples. */
    public static final int WINDOW = 3;

    private DriftEstimator() {}

    /**
     * The estimated axis-projected victim input {@code â} (b/t², + = fleeing the
     * attacker), or {@code 0.0} when fewer than two valid samples exist. Both
     * arrays are aligned, most-recent LAST, and already projected on the
     * attacker→victim axis.
     *
     * @param measuredMoves the measured per-tick displacements {@code u_t}.
     * @param knockMoves    the modeled knock displacements {@code k_t}, one per tick.
     */
    public static double estimate(double[] measuredMoves, double[] knockMoves) {
        int n = Math.min(measuredMoves.length, knockMoves.length);
        if (n < 2) {
            return 0.0; // need at least two ticks to form one â_t
        }
        double sum = 0.0;
        int used = 0;
        // Walk newest→oldest, taking the last WINDOW valid â_t. e_t = u_t − k_t.
        for (int t = n - 1; t >= 1 && used < WINDOW; t--) {
            double eT = measuredMoves[t] - knockMoves[t];
            double ePrev = measuredMoves[t - 1] - knockMoves[t - 1];
            double aHat = eT - Q * ePrev;
            if (Math.abs(aHat) > VALIDITY_BOUND) {
                continue; // model break — discard this tick, keep scanning
            }
            sum += aHat;
            used++;
        }
        if (used < 2) {
            return 0.0; // fewer than two valid samples — drop the drift term
        }
        double mean = sum / used;
        return Math.max(-CLAMP, Math.min(CLAMP, mean));
    }
}
