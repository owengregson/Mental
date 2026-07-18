package me.vexmc.mental.kernel.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JitterCalculatorTest {

    private static final long MILLI = 1_000_000L;

    @Test
    void fewerThanTwoSamplesReportZero() {
        JitterCalculator jitter = new JitterCalculator();
        assertEquals(0.0, jitter.calculateMillis());
        jitter.addPing(50 * MILLI);
        assertEquals(0.0, jitter.calculateMillis());
    }

    @Test
    void steadyConnectionHasZeroJitter() {
        JitterCalculator jitter = new JitterCalculator();
        for (int i = 0; i < 15; i++) {
            jitter.addPing(50 * MILLI);
        }
        assertEquals(0.0, jitter.calculateMillis(), 1.0e-9);
    }

    @Test
    void singleSpikeIsFilteredOutByIqr() {
        JitterCalculator jitter = new JitterCalculator();
        for (int i = 0; i < 14; i++) {
            jitter.addPing(50 * MILLI);
        }
        jitter.addPing(500 * MILLI);
        assertEquals(0.0, jitter.calculateMillis(), 1.0e-9);
    }

    @Test
    void genuineVarianceIsReported() {
        JitterCalculator jitter = new JitterCalculator();
        for (int i = 0; i < 15; i++) {
            jitter.addPing((40 + (i % 5) * 5L) * MILLI); // 40..60ms spread
        }
        double jitterMillis = jitter.calculateMillis();
        assertTrue(jitterMillis > 1.0 && jitterMillis < 15.0,
                () -> "expected single-digit jitter, got " + jitterMillis);
    }

    @Test
    void windowSlidesPastOldSamples() {
        JitterCalculator jitter = new JitterCalculator();
        for (int i = 0; i < 15; i++) {
            jitter.addPing(500 * MILLI);
        }
        for (int i = 0; i < 15; i++) {
            jitter.addPing(50 * MILLI);
        }
        assertEquals(0.0, jitter.calculateMillis(), 1.0e-9);
    }
}
