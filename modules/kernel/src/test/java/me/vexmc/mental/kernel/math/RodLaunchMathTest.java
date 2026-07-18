package me.vexmc.mental.kernel.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.random.RandomGenerator;
import me.vexmc.mental.kernel.model.KnockbackVector;
import org.junit.jupiter.api.Test;

/**
 * Expectations from the 1.8 cast formula: yaw/pitch direction × 0.4,
 * normalized, gaussian spread × 0.0075, × 1.5.
 */
class RodLaunchMathTest {

    private static final double EPSILON = 1.0e-9;

    /** Gaussian source that always returns zero — isolates the deterministic core. */
    private static final RandomGenerator ZERO_GAUSSIAN = new RandomGenerator() {
        @Override
        public long nextLong() {
            return 0;
        }

        @Override
        public double nextGaussian() {
            return 0.0;
        }
    };

    @Test
    void levelCastFacingPositiveZLaunchesStraight() {
        KnockbackVector vector = RodLaunchMath.launch(0.0f, 0.0f, ZERO_GAUSSIAN);

        assertEquals(0.0, vector.x(), EPSILON);
        assertEquals(0.0, vector.y(), EPSILON);
        assertEquals(1.5, vector.z(), EPSILON);
    }

    @Test
    void yawRotatesTheLaunchDirection() {
        KnockbackVector vector = RodLaunchMath.launch(90.0f, 0.0f, ZERO_GAUSSIAN);

        assertEquals(-1.5, vector.x(), 1.0e-6);
        assertEquals(0.0, vector.y(), EPSILON);
        assertEquals(0.0, vector.z(), 1.0e-6);
    }

    @Test
    void pitchLiftsTheCast() {
        KnockbackVector vector = RodLaunchMath.launch(0.0f, -45.0f, ZERO_GAUSSIAN);

        assertEquals(0.0, vector.x(), EPSILON);
        assertEquals(1.5 * Math.sin(Math.toRadians(45)), vector.y(), 1.0e-6);
        assertEquals(1.5 * Math.cos(Math.toRadians(45)), vector.z(), 1.0e-6);
        // toBukkit() stays behind in core; the length pin is unchanged.
        double length = Math.sqrt(
                vector.x() * vector.x() + vector.y() * vector.y() + vector.z() * vector.z());
        assertEquals(1.5, length, 1.0e-6);
    }

    @Test
    void gaussianSpreadPerturbsWithoutChangingTheScaleWildly() {
        RandomGenerator seeded = RandomGenerator.of("L64X128MixRandom");
        KnockbackVector vector = RodLaunchMath.launch(0.0f, 0.0f, seeded);

        assertTrue(Math.abs(vector.x()) < 0.1, "spread should stay small");
        assertTrue(vector.z() > 1.3 && vector.z() < 1.7);
    }
}
