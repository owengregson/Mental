package me.vexmc.mental.kernel.math;

/**
 * The vertical-axis combo shaper (COMBO_VERTICAL) — the bounded height-as-denier.
 * Given the era fresh vertical {@code V0}, a target apex elevation, and the victim's
 * launch height, it solves for the fresh vertical {@code V'} whose flight apex meets
 * the target, then clamps the adjustment to a hard bound. It is the vertical analog
 * of the horizontal {@link PocketServo}: the servo scales the fresh horizontal to
 * place the victim at a chosen separation, this shapes the fresh vertical to lift
 * them to a chosen apex — and both leave the friction-carried residual untouched.
 *
 * <h2>The solve</h2>
 * <p>The launch apex is monotone non-decreasing in the launch velocity (a harder
 * launch peaks higher), so the inverse is a simple bracketed bisection. The apex is
 * measured with the STABLE kernel helpers {@link PocketServo#arcHeight} /
 * {@link PocketServo#airTime} — the same vanilla vertical integration the pocket
 * servo's flight window uses — so the two keepers agree on the arc and this class
 * never re-derives it. The solved {@code V*} is then adjusted toward from {@code V0}
 * by at most {@code bound}: {@code V' = V0 + clamp(V* − V0, ±bound)}. Damp or lift,
 * both bounded.</p>
 *
 * <h2>The minimal-shaping guard</h2>
 * <p>The bound is the era-safety guarantee (owner directive: "if the verticals are
 * shaping a lot, something is wrong"). When {@code |V* − V0| > bound} the shaper
 * wanted more than it is allowed — it clamps AND raises {@link Result#saturated()},
 * the observable "something is wrong" signal the compute site journals. The fresh
 * vertical is never moved by more than the bound.</p>
 *
 * <h2>Fresh-vertical-only (A3 analog)</h2>
 * <p>{@code V0} is the era SHIPPED fresh vertical — it already folds in the victim's
 * friction-carried residual (the {@code vy·frictionY} term) and any
 * latency-compensation vy override. The trim adds a bounded FRESH delta on top; the
 * residual carried inside {@code V0} rides through unchanged. So this adjusts the
 * fresh vertical only — never the residual, never the horizontal — exactly as the
 * horizontal servo scales the fresh horizontal only.</p>
 *
 * <p>Pure JDK (kernel-Bukkit-free): every input is a scalar the compute seam
 * freezes.</p>
 */
public final class VerticalTrim {

    /** Below this the solve treats a launch as non-rising (apex == launch height). */
    private static final double EPSILON = 1.0e-12;

    /**
     * The bisection upper bracket for the solved launch velocity. An era vertical
     * never approaches this; a target apex demanding more just saturates the bound
     * (the exact {@code V*} beyond {@code V0 ± bound} is irrelevant — the delta
     * clamps either way), so a modest cap keeps the apex sim cheap.
     */
    private static final double SEARCH_HI = 1.0;

    /** The apex-inversion bisection tolerance / iteration cap. */
    private static final double SOLVE_TOLERANCE = 1.0e-10;
    private static final int SOLVE_ITERS = 60;

    private VerticalTrim() {}

    /**
     * The result of one shaping: the era vertical, the shipped (possibly trimmed)
     * vertical, the applied clamped delta, whether the bound saturated (the
     * observable over-shaping flag), and the target vs. achieved apex for the debug
     * sink. When the config is {@link VerticalTrimConfig#INACTIVE} the shipped equals
     * the era vertical, the delta is zero, and {@code saturated} is false
     * (byte-identical — zero-touch).
     */
    public record Result(
            double eraVertical, double shipped, double delta, boolean saturated,
            double targetApex, double achievedApex) {

        /** The identity result — used for an inactive config or a declined solve. */
        static Result identity(double eraVertical, double launchHeight) {
            return new Result(eraVertical, eraVertical, 0.0, false,
                    Double.NaN, apexHeight(eraVertical, launchHeight));
        }
    }

    /**
     * Shape the fresh vertical toward the config's target apex, bounded. Returns the
     * era vertical unchanged (identity) when the config is inactive. Otherwise solves
     * the launch velocity whose apex meets {@code targetApex}, clamps the adjustment
     * to {@code ±bound}, and reports the clamp as {@link Result#saturated()}.
     *
     * @param config       the shaper tunables (target apex + bound); INACTIVE ⇒ identity.
     * @param eraVertical  the era SHIPPED fresh vertical {@code V0} (residual + fresh,
     *                     with any latency-comp override already folded in — the trim
     *                     runs downstream of latency compensation, never re-applying it).
     * @param launchHeight the victim's feet height above the launch ground (0 grounded).
     */
    public static Result trim(VerticalTrimConfig config, double eraVertical, double launchHeight) {
        if (config == null || !config.active()) {
            return Result.identity(eraVertical, launchHeight);
        }
        double vStar = solveLaunchForApex(config.targetApex(), launchHeight);
        double desired = vStar - eraVertical;
        double bound = Math.abs(config.bound());
        boolean saturated = Math.abs(desired) > bound;
        double delta = saturated ? Math.copySign(bound, desired) : desired;
        double shipped = eraVertical + delta;
        // Defensive era-range clamp: the tiny bound already keeps V' near V0 (itself
        // within the vertical range), but a shipped vertical never exceeds the legacy
        // ±3.9 packet limit — mirror the engine's own final clamp.
        shipped = Math.max(-KnockbackEngine.PACKET_CLAMP, Math.min(KnockbackEngine.PACKET_CLAMP, shipped));
        return new Result(eraVertical, shipped, shipped - eraVertical, saturated,
                config.targetApex(), apexHeight(shipped, launchHeight));
    }

    /**
     * The peak feet-height (blocks above the launch ground) a launch of {@code
     * launchVy} from {@code launchHeight} reaches, by the vanilla vertical arc — walked
     * with {@link PocketServo#arcHeight} up to {@link PocketServo#airTime}, stopping at
     * the (unimodal) peak. A non-rising launch peaks at its launch height.
     */
    public static double apexHeight(double launchVy, double launchHeight) {
        double peak = Math.max(0.0, launchHeight);
        int air = PocketServo.airTime(launchVy, launchHeight);
        double previous = launchHeight;
        for (int t = 1; t <= air; t++) {
            double height = PocketServo.arcHeight(launchVy, launchHeight, t);
            if (height > peak) {
                peak = height;
            }
            if (height < previous) {
                break; // past the apex — the arc is unimodal (rise then fall)
            }
            previous = height;
        }
        return peak;
    }

    /**
     * The launch velocity whose apex equals {@code targetApex} at {@code launchHeight},
     * by bisection over the monotone apex(v). A target at or below the launch height is
     * unreachable downward (the victim starts there) ⇒ the minimal non-rising launch
     * (0); a target above the search bracket's apex is unreachable upward ⇒ the bracket
     * ceiling — both cases then saturate the bound in {@link #trim}. Kept package-visible
     * for the unit pins.
     */
    static double solveLaunchForApex(double targetApex, double launchHeight) {
        double lo = 0.0;
        double hi = SEARCH_HI;
        double apexLo = apexHeight(lo, launchHeight); // == max(0, launchHeight)
        if (targetApex <= apexLo + EPSILON) {
            return 0.0; // cannot apex below the launch ground — minimal launch, then damp by the bound
        }
        double apexHi = apexHeight(hi, launchHeight);
        if (targetApex >= apexHi) {
            return hi; // beyond the bracket — unreachable up (the bound will clamp the lift)
        }
        for (int i = 0; i < SOLVE_ITERS && (hi - lo) > SOLVE_TOLERANCE; i++) {
            double mid = 0.5 * (lo + hi);
            if (apexHeight(mid, launchHeight) < targetApex) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return 0.5 * (lo + hi);
    }
}
