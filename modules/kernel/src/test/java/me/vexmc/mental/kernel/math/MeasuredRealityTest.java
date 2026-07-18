package me.vexmc.mental.kernel.math;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The measured-reality vy clamp (2026-07-10-downward-kb-and-stacking-diagnoses.md,
 * report 1; the input fix designed in 2026-07-07-ledger-vy-divergence.md). The
 * {@link MotionLedger} residual models the legacy server's motY field, but for a
 * juggled airborne victim it free-falls toward the −3.92 terminal while the real
 * client hovers at −0.05…−0.34 — a MODEL bug, not era truth. Bounding the ledger
 * estimate below by the victim's measured per-tick Δy (minus a small margin that
 * absorbs the ring's sub-tick under-read) makes the input MORE era-faithful:
 * {@code max(ledgerVy, measuredVy − 0.15)}. A strict no-op when no fresh
 * measurement exists ({@link Double#NaN}), so packetless captures are byte-identical.
 */
class MeasuredRealityTest {

    private static final double EPSILON = 1.0e-9;

    @Test
    void clampsRunawayLedgerUpToMeasuredMinusMargin() {
        // The juggle divergence: ledger free-fell to −1.5, the client really
        // hovers at −0.2 → the read is bounded to −0.2 − 0.15 = −0.35 (era-positive
        // hit-2 verticals downstream), not the runaway −1.5.
        assertEquals(-0.35, MeasuredReality.clampVy(-1.5, -0.2), EPSILON);
    }

    @Test
    void noMeasurementIsAStrictNoOp() {
        // NaN measured (packetless fake, or a view built without the freeze): the
        // ledger residual passes through untouched — the pre-fix behaviour.
        assertEquals(-1.5, MeasuredReality.clampVy(-1.5, Double.NaN), EPSILON);
    }

    @Test
    void ledgerWinsWhenItAlreadySitsAboveMeasured() {
        // A faithful/rising or short-air hit: the ledger is already ABOVE
        // measured − margin, so max() keeps the ledger — the clamp never DROPS a
        // vertical, it only stops the runaway free-fall.
        assertEquals(-0.3, MeasuredReality.clampVy(-0.3, -2.0), EPSILON);
    }

    @Test
    void marginIsTheDesignedFifteenHundredths() {
        assertEquals(0.15, MeasuredReality.MARGIN, EPSILON);
    }
}
