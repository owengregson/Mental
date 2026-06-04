package me.vexmc.mental.module.fishing;

import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import me.vexmc.mental.module.knockback.KnockbackVector;
import org.jetbrains.annotations.NotNull;

/**
 * The 1.8 fishing-hook knockback formula (OCM heritage): half the victim's
 * motion carries through, 0.4 pushes along the hook→victim line normalized
 * to distance, and a 0.4 lift is capped at 0.4 so rods never launch.
 */
final class RodKnockbackMath {

    private static final double HORIZONTAL_PUSH = 0.4;
    private static final double VERTICAL_LIFT = 0.4;
    private static final double VERTICAL_CAP = 0.4;
    private static final double MOTION_CARRY = 0.5;

    private RodKnockbackMath() {}

    static @NotNull KnockbackVector knockback(
            double victimVx, double victimVy, double victimVz,
            double hookX, double hookY, double hookZ,
            double victimX, double victimZ) {
        return knockback(victimVx, victimVy, victimVz, hookX, hookY, hookZ,
                victimX, victimZ, ThreadLocalRandom.current());
    }

    static @NotNull KnockbackVector knockback(
            double victimVx, double victimVy, double victimVz,
            double hookX, double hookY, double hookZ,
            double victimX, double victimZ,
            @NotNull RandomGenerator random) {

        double deltaX = hookX - victimX;
        double deltaZ = hookZ - victimZ;
        while (deltaX * deltaX + deltaZ * deltaZ < 1.0e-4) {
            deltaX = (random.nextDouble() - random.nextDouble()) * 0.01;
            deltaZ = (random.nextDouble() - random.nextDouble()) * 0.01;
        }
        double distance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

        double x = victimVx * MOTION_CARRY - (deltaX / distance) * HORIZONTAL_PUSH;
        double y = victimVy * MOTION_CARRY + VERTICAL_LIFT;
        double z = victimVz * MOTION_CARRY - (deltaZ / distance) * HORIZONTAL_PUSH;

        if (y >= VERTICAL_CAP) {
            y = VERTICAL_CAP;
        }
        return new KnockbackVector(x, y, z);
    }
}
