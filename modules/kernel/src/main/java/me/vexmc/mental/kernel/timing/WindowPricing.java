package me.vexmc.mental.kernel.timing;

/**
 * The pure arithmetic of a temporary hit-window re-pricing (the public
 * {@code HitTimingOverrides} API). While an override is live for one
 * (victim, attacker) pair, every place Mental prices that victim's
 * hit-admission window FOR THAT ATTACKER reads {@link #price(int, double)} of
 * the profile's effective window instead of the raw window — the ct8c per-hit
 * maximum composes as {@code min(attackDelay, 10) * factor}, a plain vanilla
 * window as {@code 20 * factor}.
 *
 * <p><b>The factor floor is Mental's, not the consumer's.</b> A caller may ask
 * for any {@code factor}; {@link #clampFactor(double)} pins it into
 * {@code [0.25, 1.0]} so no integrator can price a machine-gun window (0.25 =
 * four-times-as-fast, the hard ceiling) and none can price a SLOWER window
 * (1.0 = the era-exact no-op). {@code 0.5} is the canonical "twice as fast".
 *
 * <p><b>Era-exact no-op at 1.0.</b> {@code price(w, 1.0) == w} for every window
 * — {@code round(w * 1.0) == w} — so an override held at the ceiling, and every
 * window read with no override in force (the callers pass 1.0 then), is
 * byte-identical to the un-priced path. Pure over primitives; unit-pinned with
 * hand-computed expectations.
 */
public final class WindowPricing {

    /** The hard acceleration ceiling: four-times-as-fast, the machine-gun floor Mental owns. */
    public static final double MIN_FACTOR = 0.25;

    /** The era-exact no-op: the window is unchanged (a consumer may never price a SLOWER window). */
    public static final double MAX_FACTOR = 1.0;

    private WindowPricing() {}

    /**
     * Pins {@code factor} into {@code [0.25, 1.0]}. A {@code NaN} — the only
     * value {@link Math#max}/{@link Math#min} cannot bound — degrades to the
     * {@link #MAX_FACTOR} no-op, so a malformed request never accelerates.
     */
    public static double clampFactor(double factor) {
        if (Double.isNaN(factor)) {
            return MAX_FACTOR;
        }
        return Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, factor));
    }

    /**
     * The re-priced window: {@code round(effectiveWindow * clampFactor(factor))}.
     * The factor is clamped here too so the arithmetic can never be fed an
     * out-of-range value regardless of call site. A zero window (a projectile's
     * {@code 0}-tick ct8c window) stays zero at every factor.
     */
    public static int price(int effectiveWindow, double factor) {
        return (int) Math.round(effectiveWindow * clampFactor(factor));
    }
}
