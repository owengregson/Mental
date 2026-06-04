package me.vexmc.mental.module.knockback;

import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import me.vexmc.mental.config.KnockbackSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Pure 1.8.x knockback math. Thread-agnostic: inputs are immutable
 * {@link EntityState} captures, so the same formula serves the main-thread
 * event path and the netty fast path bit-for-bit.
 *
 * <pre>
 *   1. Direction = (attacker − victim) on the horizontal plane, normalized;
 *      coincident positions get a tiny random direction (vanilla behavior).
 *   2. base  = victimVelocity × friction − direction × baseHorizontal,
 *      baseY = victimVy × frictionY + baseVertical
 *      (victimVy comes from the latency-compensation override when present).
 *   3. Horizontal cap rescales (x, z) if configured.
 *   4. Vertical cap clamps the BASE y — vanilla 1.8 ordering, so bonus
 *      levels can push past it (sprint hits reach 0.5).
 *   5. Sprint and Knockback enchant levels add the extra vector along the
 *      attacker's facing.
 *   6. Armor knockback resistance optionally scales the horizontal result.
 * </pre>
 */
public final class KnockbackEngine {

    private KnockbackEngine() {}

    public static @NotNull KnockbackVector compute(
            @NotNull EntityState attacker,
            @NotNull EntityState victim,
            @NotNull KnockbackSettings settings,
            @Nullable Double victimYOverride) {
        return compute(attacker, victim, settings, victimYOverride, ThreadLocalRandom.current());
    }

    public static @NotNull KnockbackVector compute(
            @NotNull EntityState attacker,
            @NotNull EntityState victim,
            @NotNull KnockbackSettings settings,
            @Nullable Double victimYOverride,
            @NotNull RandomGenerator random) {

        double deltaX = attacker.x() - victim.x();
        double deltaZ = attacker.z() - victim.z();
        if (deltaX * deltaX + deltaZ * deltaZ < 1.0e-4) {
            deltaX = (random.nextDouble() - random.nextDouble()) * 0.01;
            deltaZ = (random.nextDouble() - random.nextDouble()) * 0.01;
            if (deltaX * deltaX + deltaZ * deltaZ < 1.0e-8) {
                deltaX = 0.01;
            }
        }
        double magnitude = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

        double victimVy = victimYOverride != null ? victimYOverride : victim.vy();
        double x = victim.vx() * settings.frictionX() - (deltaX / magnitude) * settings.baseHorizontal();
        double y = victimVy * settings.frictionY() + settings.baseVertical();
        double z = victim.vz() * settings.frictionZ() - (deltaZ / magnitude) * settings.baseHorizontal();

        if (settings.limitsHorizontal()) {
            double horizontal = Math.hypot(x, z);
            if (horizontal > settings.limitHorizontal()) {
                double scale = settings.limitHorizontal() / horizontal;
                x *= scale;
                z *= scale;
            }
        }

        if (settings.limitsVertical() && y > settings.limitVertical()) {
            y = settings.limitVertical();
        }

        double bonusLevel = (attacker.sprinting() ? settings.sprintFactor() : 0.0)
                + attacker.knockbackEnchantLevel();
        if (bonusLevel > 0) {
            double yawRadians = Math.toRadians(attacker.yaw());
            x += -Math.sin(yawRadians) * bonusLevel * settings.extraHorizontal();
            z += Math.cos(yawRadians) * bonusLevel * settings.extraHorizontal();
            y += settings.extraVertical();
        }

        if (settings.honorArmorResistance() && victim.knockbackResistance() > 0.0) {
            double survives = 1.0 - victim.knockbackResistance();
            x *= survives;
            z *= survives;
        }

        return new KnockbackVector(x, y, z);
    }
}
