package me.vexmc.mental.kernel.math;

import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import me.vexmc.mental.kernel.model.KnockbackVector;

/**
 * The 1.7.10 fishing-hook cast velocity: direction from yaw/pitch × 0.4,
 * normalized, perturbed by the legacy exact gaussian spread, scaled × 1.5.
 */
public final class RodLaunchMath {

    private static final float CAST_SPEED = 0.4f;
    private static final double SPREAD = 0.007499999832361937;
    private static final double LAUNCH_MULTIPLIER = 1.5;

    private RodLaunchMath() {}

    public static KnockbackVector launch(float yaw, float pitch) {
        return launch(yaw, pitch, ThreadLocalRandom.current());
    }

    public static KnockbackVector launch(float yaw, float pitch, RandomGenerator random) {
        double yawRadians = yaw / 180.0f * (float) Math.PI;
        double pitchRadians = pitch / 180.0f * (float) Math.PI;

        double x = -Math.sin(yawRadians) * Math.cos(pitchRadians) * CAST_SPEED;
        double y = -Math.sin(pitchRadians) * CAST_SPEED;
        double z = Math.cos(yawRadians) * Math.cos(pitchRadians) * CAST_SPEED;

        double length = Math.sqrt(x * x + y * y + z * z);
        x /= length;
        y /= length;
        z /= length;

        x += random.nextGaussian() * SPREAD;
        y += random.nextGaussian() * SPREAD;
        z += random.nextGaussian() * SPREAD;

        return new KnockbackVector(x * LAUNCH_MULTIPLIER, y * LAUNCH_MULTIPLIER, z * LAUNCH_MULTIPLIER);
    }
}
