package me.vexmc.mental.kernel.math;

/**
 * The measured-reality clamp on the ledger's vertical residual — the single pure
 * home of the input fix designed in {@code 2026-07-07-ledger-vy-divergence.md} and
 * shipped this round per {@code 2026-07-10-downward-kb-and-stacking-diagnoses.md}
 * (report 1, the downward hit-2 root).
 *
 * <p>The {@link me.vexmc.mental.kernel.ledger.MotionLedger} residual models the
 * legacy server's per-tick {@code motY} field — a latency-compensated ESTIMATE of
 * the victim's real flight. For a juggled airborne victim (15–28 tick air stretches,
 * no landing between hits) that estimate free-falls toward the −3.92 terminal
 * (−0.96…−1.50 measured on 914 boxer hits) while the real client only hovers at
 * −0.05…−0.34, re-lifted by each knock. The era server's motY tracked the real
 * client (its jump bookkeeping re-stamped it every rising tick), so the runaway
 * free-fall is a MODEL bug, not era truth. Bounding the estimate below by the
 * victim's measured per-tick Δy makes the knockback input MORE era-faithful, not
 * less.</p>
 *
 * <p>Vertical only, and never DROPS a vertical: {@code max(ledgerVy, measuredVy −
 * MARGIN)} keeps the ledger whenever it already sits at or above the measured floor
 * (a faithful rising / short-air hit), and only stops the runaway free-fall. The
 * {@link #MARGIN} absorbs the position ring's sub-tick under-read so a genuinely
 * rising hit is never clamped. A {@link Double#NaN} measurement (a packetless
 * capture, or any view built without the freeze) is a STRICT no-op — the residual
 * passes through byte-identically, so every existing suite and every packetless
 * fake keep their exact behaviour.</p>
 */
public final class MeasuredReality {

    private MeasuredReality() {}

    /**
     * The margin subtracted from the measured Δy before it bounds the ledger — it
     * absorbs the position ring's sub-tick under-read (the measured delta lags the
     * true motion slightly), so a faithful rising or short-air hit is never clamped.
     * 0.15 b/t, the value the divergence research pinned against the live capture
     * (it would have bitten exactly the leak class: 5/608 legacy-1.8, 0/127 legacy-1.7).
     */
    public static final double MARGIN = 0.15;

    /**
     * The victim's effective vertical for the knockback read: the ledger residual
     * bounded below by the measured reality. {@code measuredVy} is the victim's
     * published per-tick position Δy (end of the previous tick, the same boundary
     * the residual rides); {@link Double#NaN} means no fresh measurement — then the
     * ledger residual is returned unchanged (strict no-op).
     */
    public static double clampVy(double ledgerVy, double measuredVy) {
        if (Double.isNaN(measuredVy)) {
            return ledgerVy;
        }
        return Math.max(ledgerVy, measuredVy - MARGIN);
    }
}
