package me.vexmc.mental.tester.suite;

import java.util.List;
import me.vexmc.mental.kernel.math.Decay;
import org.jetbrains.annotations.NotNull;

/**
 * The legacy server's motion integrator, replayed over captured velocity
 * events — the reference a knocked player's <em>final position</em> is
 * compared against.
 *
 * <p>This is the decompiled 1.7.10 {@code EntityLivingBase} step (identical
 * in 1.8.9, and — for an unpropelled knocked player — in every modern
 * version too, which is precisely what the era-parity suite asserts):
 * per tick, the horizontal drag factor is chosen from the ground state
 * <em>before</em> the move ({@code × 0.91} airborne, {@code × 0.91 × 0.6}
 * on stone — so a knock's launch tick still decays at ground friction,
 * which is why a 0.4 hit travels ~2.0 blocks and not 3), then position
 * moves by the motion, vertical collision with the floor lands the entity,
 * and the motion decays: {@code motY = (motY − 0.08) × 0.98}, horizontal by
 * the pre-move factor. A velocity packet replaces all three axes before the
 * move of its tick, exactly as a client applies it.</p>
 */
final class EraOracle {

    // Derived from the kernel motion authority — the suite never re-declares a
    // physics constant of its own (spec §12.7). GROUND_DRAG is the era default
    // slipperiness × air drag (stone: 0.6 × 0.91 = 0.546).
    private static final double GRAVITY = Decay.DEFAULT_GRAVITY;
    private static final double VERTICAL_DRAG = Decay.VERTICAL_DRAG;
    private static final double AIR_DRAG = Decay.AIR_DRAG;
    private static final double GROUND_DRAG = Decay.DEFAULT_SLIPPERINESS * Decay.AIR_DRAG;

    /** Motion replaced (all axes) before the move of tick {@code tick}. */
    record VelocityEvent(int tick, double vx, double vy, double vz) {}

    /**
     * Continuous movement input held through the knock flight — the
     * "keeps walking/sprinting at you" victim. The acceleration is the era
     * (and modern) air value applied before each airborne move, exactly
     * where the client integrates held keys; it stops on landing, when the
     * combat flight ends. Air accel: 0.02 walking, 0.026 sprinting.
     */
    record Input(double dirX, double dirZ, double airAccel) {}

    record Result(double x, double y, double z, boolean grounded) {

        double distanceFrom(double startX, double startZ) {
            return Math.hypot(x - startX, z - startZ);
        }
    }

    private EraOracle() {}

    static @NotNull Result simulate(
            double startX, double startY, double startZ, boolean startGrounded,
            double floorY, @NotNull List<VelocityEvent> events, int ticks) {
        return simulate(startX, startY, startZ, startGrounded, floorY, events, ticks, null);
    }

    static @NotNull Result simulate(
            double startX, double startY, double startZ, boolean startGrounded,
            double floorY, @NotNull List<VelocityEvent> events, int ticks,
            @org.jetbrains.annotations.Nullable Input input) {

        double x = startX;
        double y = startY;
        double z = startZ;
        double vx = 0;
        double vy = 0;
        double vz = 0;
        boolean grounded = startGrounded;
        boolean inputActive = input != null;
        boolean flewSinceKnock = false;

        for (int tick = 0; tick < ticks; tick++) {
            for (VelocityEvent event : events) {
                if (event.tick() == tick) {
                    vx = event.vx();
                    vy = event.vy();
                    vz = event.vz();
                }
            }

            // Held input integrates before the move, air strength, until the
            // post-knock flight touches down.
            if (inputActive) {
                if (!grounded) {
                    flewSinceKnock = true;
                    vx += input.dirX() * input.airAccel();
                    vz += input.dirZ() * input.airAccel();
                } else if (flewSinceKnock) {
                    inputActive = false;
                }
            }

            // The drag factor is locked in from the PRE-move ground state —
            // the moveEntityWithHeading order on every version.
            double drag = grounded ? GROUND_DRAG : AIR_DRAG;

            // moveEntity: horizontal is unobstructed on the runway; vertical
            // collides with the flat floor.
            x += vx;
            z += vz;
            double nextY = y + vy;
            if (vy <= 0 && nextY <= floorY) {
                y = floorY;
                vy = 0;
                grounded = true;
            } else {
                y = nextY;
                grounded = false;
            }

            vy = (vy - GRAVITY) * VERTICAL_DRAG;
            vx *= drag;
            vz *= drag;
        }
        return new Result(x, y, z, grounded);
    }
}
