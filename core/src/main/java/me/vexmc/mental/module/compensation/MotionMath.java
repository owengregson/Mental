package me.vexmc.mental.module.compensation;

/**
 * Vertical-motion simulation primitives, adapted from KnockbackSync
 * (GPL-3.0 — see README attribution).
 *
 * <p>Vanilla ticks vertical motion as {@code v ← (v − g) × 0.98}, clamped at
 * terminal velocity. Each method walks that loop forward; {@code -1} means
 * the simulation exceeded {@link #MAX_TICKS} and the caller should refuse to
 * predict (someone rocketing through the air for half a second is not a
 * candidate for confident knockback rewriting).</p>
 */
final class MotionMath {

    static final double TERMINAL_VELOCITY = 3.92;
    static final double DRAG_MULTIPLIER = 0.98;
    static final int MAX_TICKS = 30;

    private MotionMath() {}

    /** Velocity after {@code ticks} ticks of vanilla decay (positive = upward). */
    static double simulateVerticalVelocity(double velocity, double gravity, int ticks) {
        for (int i = 0; i < ticks; i++) {
            velocity = (velocity - gravity) * DRAG_MULTIPLIER;
            if (velocity < -TERMINAL_VELOCITY) {
                velocity = -TERMINAL_VELOCITY;
            }
        }
        return velocity;
    }

    /** Total upward distance travelled over {@code ticks} ticks from {@code velocity}. */
    static double distanceTraveled(double velocity, int ticks, double gravity) {
        double total = 0.0;
        for (int i = 0; i < ticks; i++) {
            total += velocity;
            velocity = (velocity - gravity) * DRAG_MULTIPLIER;
            if (velocity > TERMINAL_VELOCITY) {
                velocity = TERMINAL_VELOCITY;
            }
        }
        return total;
    }

    /** Ticks for an upward {@code velocity} to decay to ≤ 0, or -1 past the cap. */
    static int ticksToApex(double velocity, double gravity) {
        int ticks = 0;
        while (velocity > 0) {
            if (ticks > MAX_TICKS) {
                return -1;
            }
            velocity = (velocity - gravity) * DRAG_MULTIPLIER;
            if (velocity > TERMINAL_VELOCITY) {
                velocity = TERMINAL_VELOCITY;
            }
            ticks++;
        }
        return ticks;
    }

    /**
     * Ticks to fall {@code distance} blocks from {@code initialVelocity}
     * (gravity signed positive downward — falling phase only), or -1 past the cap.
     */
    static int ticksToFall(double initialVelocity, double distance, double gravity) {
        double velocity = Math.abs(initialVelocity);
        int ticks = 0;
        while (distance > 0) {
            if (ticks > MAX_TICKS) {
                return -1;
            }
            velocity += gravity;
            if (velocity > TERMINAL_VELOCITY) {
                velocity = TERMINAL_VELOCITY;
            }
            velocity *= DRAG_MULTIPLIER;
            distance -= velocity;
            ticks++;
        }
        return ticks;
    }
}
