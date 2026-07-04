package me.vexmc.mental.kernel.math;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins for the history-free victim-drift estimator (combo-hold §3.2b;
 * precision-derivation §2.4/§7.1). Synthetic ring traces with a KNOWN held input
 * are fed through the exact-decay subtraction {@code â_t = (u_t − q·u_{t-1}) −
 * (k_t − q·k_{t-1})}; the recovered â must equal the planted input, the validity
 * gate must discard model-break spikes, and the clamp must cap the estimate.
 */
class DriftEstimatorTest {

    private static final double EPSILON = 1.0e-12;
    private static final double Q = Decay.AIR_DRAG; // 0.91

    /** A synthetic knock trajectory: a decaying horizontal move per tick (any shape works). */
    private static double[] knockTrace(int n) {
        double[] k = new double[n];
        double v = 0.7;
        for (int t = 0; t < n; t++) {
            k[t] = v;
            v *= Q;
        }
        return k;
    }

    /** Measured moves for a CONSTANT held input {@code a}: the knock plus the input residual e_t. */
    private static double[] measuredForConstantInput(double[] knock, double a) {
        double[] u = new double[knock.length];
        double e = 0.0; // the stamp wiped the drift velocity — rebuild from 0
        for (int t = 0; t < knock.length; t++) {
            e = Q * e + a;      // e_t = q·e_{t-1} + a_t
            u[t] = knock[t] + e;
        }
        return u;
    }

    @Test
    void pureKnockRecoversZeroInput() {
        double[] knock = knockTrace(6);
        double[] measured = knock.clone(); // no input: measured == modeled knock
        assertEquals(0.0, DriftEstimator.estimate(measured, knock), EPSILON);
    }

    @Test
    void heldBackwardInputRecoversExactly() {
        // Holding S airborne: straight-line walk-air Δv = 0.0196 (§2.1). Every â_t
        // recovers it exactly, so the mean is 0.0196 (below the 0.0264 clamp).
        double[] knock = knockTrace(6);
        double a = 0.0196;
        double[] measured = measuredForConstantInput(knock, a);
        assertEquals(a, DriftEstimator.estimate(measured, knock), 1.0e-9);
    }

    @Test
    void inputFlipIsTrackedWithinTheWindow() {
        // Input flips sign at tick 3 (S→W); the N=3 window over the newest samples
        // recovers the NEW input, not the old one.
        int n = 8;
        double[] knock = knockTrace(n);
        double[] u = new double[n];
        double e = 0.0;
        for (int t = 0; t < n; t++) {
            double a = t < 3 ? 0.0196 : -0.0196;
            e = Q * e + a;
            u[t] = knock[t] + e;
        }
        assertEquals(-0.0196, DriftEstimator.estimate(u, knock), 1.0e-9,
                "the newest three â_t all carry the flipped input");
    }

    @Test
    void aModelBreakSpikeIsDiscarded() {
        // A single aliased sample (a 0/2-packet tick) makes one â_t exceed the 0.03
        // physics bound; it is gated out and the remaining valid samples still average
        // to the true input.
        int n = 7;
        double[] knock = knockTrace(n);
        double a = 0.0196;
        double[] u = measuredForConstantInput(knock, a);
        u[n - 2] += 0.5; // a spike two ticks back — corrupts â at n-2 and n-1
        // With the newest two â_t poisoned and discarded (|â_t| > 0.03), the estimator
        // falls back on the still-valid older â_t, which recover the true 0.0196.
        assertEquals(a, DriftEstimator.estimate(u, knock), 1.0e-9);
    }

    @Test
    void theEstimateIsClampedToThePhysicsBound() {
        // A valid-but-large input (0.028, under the 0.03 gate but over the 0.0264
        // clamp) is capped at the clamp — no held key can exceed the sprint-air rate.
        double[] knock = knockTrace(6);
        double[] measured = measuredForConstantInput(knock, 0.028);
        assertEquals(DriftEstimator.CLAMP, DriftEstimator.estimate(measured, knock), 1.0e-9);
    }

    @Test
    void tooFewSamplesDropsTheTerm() {
        // Fewer than two valid â_t → 0 (the drift term drops, the base solve stands).
        double[] one = {0.5};
        assertEquals(0.0, DriftEstimator.estimate(one, new double[] {0.5}), 0.0);
    }
}
