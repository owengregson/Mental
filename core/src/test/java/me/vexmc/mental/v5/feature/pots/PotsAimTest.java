package me.vexmc.mental.v5.feature.pots;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.vexmc.mental.v5.feature.pots.PotsAim.Aim;
import org.junit.jupiter.api.Test;

/**
 * Hand-computed pins for the fast-pots aim. The load-bearing one is the magnitude
 * invariant — the feature's whole contract is "speed = multiplier × vanilla",
 * which {@link PotsAim} guarantees by normalising the direction and scaling to the
 * target speed — plus two closed-form geometry pins derived by hand, not by
 * re-running the production code.
 */
class PotsAimTest {

    private static final double EXACT = 1.0e-9;

    /**
     * A resting thrower whose feet are directly below the launch point: the only
     * lever is straight down, so the velocity is exactly {@code (0, -S, 0)}.
     */
    @Test
    void restingSelfThrowAimsStraightDownAtTargetSpeed() {
        Aim aim = PotsAim.aim(
                0.0, 1.62, 0.0,   // launch (eye)
                0.0, 0.0, 0.0,    // feet now
                0.0, 0.0, 0.0,    // thrower velocity
                1.5);             // target speed
        assertEquals(0.0, aim.x(), EXACT, "no horizontal lever");
        assertEquals(-1.5, aim.y(), EXACT, "straight down at the target speed");
        assertEquals(0.0, aim.z(), EXACT, "no horizontal lever");
        assertEquals(1.5, aim.magnitude(), EXACT, "magnitude is exactly the target speed");
    }

    /**
     * A moving thrower, feet directly below the launch in x/z. Closed form: because
     * the launch sits directly above the feet horizontally, the potion must match
     * the thrower's horizontal velocity to track the moving feet (dx = vx·t and the
     * scale is 1/t, so x = vx exactly at convergence), and the remaining speed
     * budget goes straight down. Here vx = 0.2, so x = 0.2 and
     * y = -sqrt(1.5² − 0.2²) = -sqrt(2.21). Three passes settle to within 1e-4.
     */
    @Test
    void movingThrowerTracksHorizontalVelocityAndSpendsTheRestVertically() {
        Aim aim = PotsAim.aim(
                0.0, 1.62, 0.0,
                0.0, 0.0, 0.0,
                0.2, 0.0, 0.0,
                1.5);
        assertEquals(0.2, aim.x(), 1.0e-4, "horizontal velocity tracks the feet's own motion");
        assertEquals(0.0, aim.z(), EXACT, "no z lever");
        assertEquals(-Math.sqrt(2.21), aim.y(), 1.0e-4, "remaining speed budget goes downward");
        assertEquals(1.5, aim.magnitude(), EXACT, "magnitude invariant holds under motion");
        assertTrue(aim.y() < 0.0, "the aim points downward toward the feet");
    }

    /** The magnitude invariant holds for arbitrary, asymmetric geometry and motion. */
    @Test
    void magnitudeAlwaysEqualsTheTargetSpeed() {
        Aim aim = PotsAim.aim(
                2.0, 3.0, -1.0,
                5.0, 0.5, 4.0,
                0.1, -0.02, 0.3,
                2.0);
        assertEquals(2.0, aim.magnitude(), EXACT,
                "the direction is normalised then scaled, so |v| is exactly the target speed");
    }

    /** Launch and feet coincide with no motion — no lever to aim along, so straight down. */
    @Test
    void degenerateCoincidentPointsFallBackToStraightDown() {
        Aim aim = PotsAim.aim(
                4.0, 10.0, -2.0,
                4.0, 10.0, -2.0,
                0.0, 0.0, 0.0,
                1.5);
        assertEquals(0.0, aim.x(), EXACT);
        assertEquals(-1.5, aim.y(), EXACT);
        assertEquals(0.0, aim.z(), EXACT);
    }

    /** A zero (or negative) target speed cannot aim anything — a defined zero result. */
    @Test
    void nonPositiveTargetSpeedYieldsZero() {
        Aim aim = PotsAim.aim(0.0, 1.62, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        assertEquals(0.0, aim.magnitude(), EXACT);
    }
}
