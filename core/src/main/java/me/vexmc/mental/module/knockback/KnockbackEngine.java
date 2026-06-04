package me.vexmc.mental.module.knockback;

import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import me.vexmc.mental.config.KnockbackSettings;
import me.vexmc.mental.config.ResistancePolicy;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Pure 1.7.10 knockback math (byte-identical to 1.8.9 — only delivery
 * changed between those versions). Thread-agnostic: inputs are immutable
 * {@link EntityState} captures, so the same formula serves the main-thread
 * event path and the netty fast path bit-for-bit.
 *
 * <pre>
 *   1. LEGACY resistance rolls all-or-nothing first, exactly where the
 *      legacy {@code knockBack} early-returned; null means no knockback.
 *   2. Direction = (source − victim) on the horizontal plane, normalized;
 *      coincident positions get a tiny random direction (vanilla behavior).
 *      The source is a position: attacker for melee, angler for rods,
 *      shooter for projectiles — never the projectile itself.
 *   3. base  = victimMotion × friction − direction × baseHorizontal,
 *      baseY = victimVy × frictionY + baseVertical.
 *      The victim motion is the {@link VictimMotion} residual — the legacy
 *      server fields — which is what makes successive hits compound.
 *   4. Horizontal cap rescales (x, z) if configured; the vertical cap
 *      clamps the BASE y, so bonus levels push past it (sprint hits reach
 *      0.5 — the vanilla ordering).
 *   5. Melee only: sprint and Knockback enchant levels add the extra
 *      vector along the attacker's facing ({@code addVelocity} semantics —
 *      additive, never resistance-scaled).
 *   6. SCALING resistance multiplies the horizontal result.
 *   7. Axes clamp to ±3.9, the legacy velocity-packet encoding limit.
 * </pre>
 */
public final class KnockbackEngine {

    /** The legacy velocity packet clamped each axis to ±3.9 ({@code motion × 8000} shorts). */
    public static final double PACKET_CLAMP = 3.9;

    private KnockbackEngine() {}

    /** Melee knockback: base push away from the attacker plus yaw-directed bonus levels. */
    public static @Nullable KnockbackVector compute(
            @NotNull EntityState attacker,
            @NotNull EntityState victim,
            @NotNull KnockbackSettings settings,
            @Nullable Double victimYOverride) {
        return compute(attacker, victim, settings, victimYOverride, ThreadLocalRandom.current());
    }

    public static @Nullable KnockbackVector compute(
            @NotNull EntityState attacker,
            @NotNull EntityState victim,
            @NotNull KnockbackSettings settings,
            @Nullable Double victimYOverride,
            @NotNull RandomGenerator random) {

        if (resistanceCancels(victim, settings, random)) {
            return null;
        }
        double[] vector = base(victim, attacker.x(), attacker.z(), settings, victimYOverride, random);

        double bonusLevel = (attacker.sprinting() ? settings.sprintFactor() : 0.0)
                + attacker.knockbackEnchantLevel();
        if (bonusLevel > 0) {
            double yawRadians = Math.toRadians(attacker.yaw());
            vector[0] += -Math.sin(yawRadians) * bonusLevel * settings.extraHorizontal();
            vector[2] += Math.cos(yawRadians) * bonusLevel * settings.extraHorizontal();
            vector[1] += settings.extraVertical();
        }

        return finish(vector, victim, settings);
    }

    /**
     * Base-only knockback away from a source position — the bare legacy
     * {@code knockBack(0.4)} that rod bobbers and thrown projectiles
     * triggered. No bonus levels exist on this path.
     */
    public static @Nullable KnockbackVector computeBase(
            @NotNull EntityState victim,
            double sourceX,
            double sourceZ,
            @NotNull KnockbackSettings settings,
            @Nullable Double victimYOverride,
            @NotNull RandomGenerator random) {

        if (resistanceCancels(victim, settings, random)) {
            return null;
        }
        return finish(base(victim, sourceX, sourceZ, settings, victimYOverride, random), victim, settings);
    }

    /** Clamps each axis to the legacy packet limit; bonus additions re-clamp through this. */
    public static @NotNull KnockbackVector clamp(double x, double y, double z) {
        return new KnockbackVector(
                Math.max(-PACKET_CLAMP, Math.min(PACKET_CLAMP, x)),
                Math.max(-PACKET_CLAMP, Math.min(PACKET_CLAMP, y)),
                Math.max(-PACKET_CLAMP, Math.min(PACKET_CLAMP, z)));
    }

    private static boolean resistanceCancels(
            EntityState victim, KnockbackSettings settings, RandomGenerator random) {
        return settings.resistance() == ResistancePolicy.LEGACY
                && victim.knockbackResistance() > 0.0
                && random.nextDouble() < victim.knockbackResistance();
    }

    private static double[] base(
            EntityState victim,
            double sourceX,
            double sourceZ,
            KnockbackSettings settings,
            @Nullable Double victimYOverride,
            RandomGenerator random) {

        double deltaX = sourceX - victim.x();
        double deltaZ = sourceZ - victim.z();
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
        return new double[] {x, y, z};
    }

    private static KnockbackVector finish(double[] vector, EntityState victim, KnockbackSettings settings) {
        if (settings.resistance() == ResistancePolicy.SCALING && victim.knockbackResistance() > 0.0) {
            double survives = 1.0 - victim.knockbackResistance();
            vector[0] *= survives;
            vector[2] *= survives;
        }
        return clamp(vector[0], vector[1], vector[2]);
    }
}
