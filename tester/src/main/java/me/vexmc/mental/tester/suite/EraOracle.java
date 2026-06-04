package me.vexmc.mental.tester.suite;

import java.util.List;
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

    private static final double GRAVITY = 0.08;
    private static final double VERTICAL_DRAG = 0.98;
    private static final double AIR_DRAG = 0.91;
    private static final double GROUND_DRAG = 0.91 * 0.6;

    /** Motion replaced (all axes) before the move of tick {@code tick}. */
    record VelocityEvent(int tick, double vx, double vy, double vz) {}

    record Result(double x, double y, double z, boolean grounded) {

        double distanceFrom(double startX, double startZ) {
            return Math.hypot(x - startX, z - startZ);
        }
    }

    private EraOracle() {}

    static @NotNull Result simulate(
            double startX, double startY, double startZ, boolean startGrounded,
            double floorY, @NotNull List<VelocityEvent> events, int ticks) {

        double x = startX;
        double y = startY;
        double z = startZ;
        double vx = 0;
        double vy = 0;
        double vz = 0;
        boolean grounded = startGrounded;

        for (int tick = 0; tick < ticks; tick++) {
            for (VelocityEvent event : events) {
                if (event.tick() == tick) {
                    vx = event.vx();
                    vy = event.vy();
                    vz = event.vz();
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
