package me.vexmc.mental.kernel.math;

/**
 * Vertical motion prediction over the vanilla living-entity recurrence —
 * clean-room, derived from the physics in the combat compendium (§2):
 * per tick the entity first MOVES by its current motion, then the fields
 * decay {@code motY = (motY − gravity) × 0.98}, clamped at the −3.92
 * terminal velocity. That move-then-decay order is why a 0.42 jump's first
 * position delta is the full 0.42 and its apex is ~1.2522 blocks.
 *
 * <p>All predictions are capped at {@link #MAX_PREDICTION_TICKS} ticks
 * (1.5 seconds — far past any playable compensation window); a question
 * whose answer lies beyond the cap returns −1 rather than a guess.</p>
 */
public final class MotionMath {

    /** The drag asymptote: {@code −0.0784 / (1 − 0.98)}. Falls clamp here. */
    public static final double TERMINAL_VELOCITY = 3.92;

    /** The vertical drag applied after the gravity subtraction, every tick. */
    public static final double DRAG_MULTIPLIER = 0.98;

    /** Prediction horizon: questions unanswered within 30 ticks return −1. */
    public static final int MAX_PREDICTION_TICKS = 30;

    private MotionMath() {}

    /** One decay step: {@code (v − gravity) × 0.98}, clamped at terminal. */
    private static double step(double velocity, double gravity) {
        double next = (velocity - gravity) * DRAG_MULTIPLIER;
        return next < -TERMINAL_VELOCITY ? -TERMINAL_VELOCITY : next;
    }

    /** The vertical velocity {@code ticks} decay steps after {@code initial}. */
    public static double simulateVerticalVelocity(double initial, double gravity, int ticks) {
        double velocity = initial;
        for (int i = 0; i < ticks; i++) {
            velocity = step(velocity, gravity);
        }
        return velocity;
    }

    /**
     * Net signed vertical displacement after {@code ticks} physics ticks
     * from velocity {@code initial}: each tick moves by the CURRENT
     * velocity, then the velocity decays (the vanilla order). Positive is
     * upward; a launch that has passed apex contributes its descent.
     */
    public static double distanceTraveled(double initial, int ticks, double gravity) {
        double velocity = initial;
        double displacement = 0.0;
        for (int i = 0; i < ticks; i++) {
            displacement += velocity;
            velocity = step(velocity, gravity);
        }
        return displacement;
    }

    /**
     * Ticks of upward travel left in {@code velocity}: the number of decay
     * steps until the vertical velocity is no longer positive. Zero for a
     * resting or descending entity; −1 when the apex lies beyond the
     * prediction horizon.
     */
    public static int ticksToApex(double velocity, double gravity) {
        double v = velocity;
        int ticks = 0;
        while (v > 0.0) {
            if (ticks >= MAX_PREDICTION_TICKS) {
                return -1;
            }
            v = step(v, gravity);
            ticks++;
        }
        return ticks;
    }

    /**
     * Ticks until an entity launched at {@code initial} has fallen
     * {@code distance} blocks below its starting height — ONE continuous
     * signed-velocity simulation, so a positive launch rises, tops out and
     * descends within the same fold (never a rise-phase plus a separate
     * drop-phase estimate). −1 when the landing lies beyond the prediction
     * horizon.
     */
    public static int ticksToFall(double initial, double distance, double gravity) {
        double velocity = initial;
        double displacement = 0.0;
        int ticks = 0;
        while (ticks < MAX_PREDICTION_TICKS) {
            displacement += velocity;
            velocity = step(velocity, gravity);
            ticks++;
            if (displacement <= -distance) {
                return ticks;
            }
        }
        return -1;
    }
}
