package me.vexmc.mental.kernel.math;

import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import me.vexmc.mental.kernel.model.EntityState;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.kernel.profile.ResistancePolicy;
import me.vexmc.mental.kernel.profile.VerticalMode;

/**
 * Pure knockback math, parameterized by a {@link KnockbackProfile}. With the
 * default {@code legacy-1.7} profile this is byte-identical to the 1.7.10
 * formula (which is itself byte-identical to 1.8.9 — only delivery changed
 * between those versions); the remaining knobs are the improved-knockback
 * fork vocabulary, each an era-exact no-op until a profile sets it.
 * Thread-agnostic: inputs are immutable {@link EntityState} captures, so the
 * same formula serves the main-thread event path and the netty fast path
 * bit-for-bit.
 *
 * <pre>
 *   1. LEGACY resistance rolls all-or-nothing first, exactly where the
 *      legacy {@code knockBack} early-returned; null means no knockback.
 *   2. Direction = (source − victim) on the horizontal plane, normalized;
 *      coincident positions get a tiny random direction (vanilla behavior).
 *      The source is a position: attacker for melee, angler for rods,
 *      shooter for projectiles — never the projectile itself.
 *   3. Melee only: the range-reduction taper shaves the horizontal base
 *      push for hits beyond its start distance (the MMC reach softening).
 *   4. base  = victimMotion × friction − direction × push,
 *      baseY = victimVy × frictionY + baseVertical  (vertical-mode ADD)
 *            = baseVertical                          (vertical-mode SET).
 *      The victim motion is the {@code VictimMotion} residual — the legacy
 *      server fields — which is what makes successive hits compound.
 *   5. Horizontal cap rescales (x, z) if configured; the vertical cap
 *      clamps the BASE y, so bonus levels push past it (sprint hits reach
 *      0.5 — the vanilla ordering).
 *   6. Melee only: sprint and Knockback enchant levels add the extra
 *      vector along the attacker's facing ({@code addVelocity} semantics —
 *      additive, never resistance-scaled). A freshly re-engaged sprint
 *      (w-tap) uses the {@code wtap-extra} pair when the profile enables it;
 *      enchant levels always use {@code extra}.
 *   7. Air multipliers scale the whole vector for airborne victims, the
 *      add offsets shift it sign-matched, and the vertical floor clamps —
 *      all identity at their defaults.
 *   8. SCALING resistance multiplies the horizontal result.
 *   9. Axes clamp to ±3.9, the legacy velocity-packet encoding limit.
 * </pre>
 */
public final class KnockbackEngine {

    /** The legacy velocity packet clamped each axis to ±3.9 ({@code motion × 8000} shorts). */
    public static final double PACKET_CLAMP = 3.9;

    private KnockbackEngine() {}

    /** Melee knockback: base push away from the attacker plus yaw-directed bonus levels. */
    public static KnockbackVector compute(
            EntityState attacker,
            EntityState victim,
            KnockbackProfile profile,
            Double victimYOverride) {
        return compute(attacker, victim, profile, victimYOverride, ThreadLocalRandom.current(), false);
    }

    public static KnockbackVector compute(
            EntityState attacker,
            EntityState victim,
            KnockbackProfile profile,
            Double victimYOverride,
            RandomGenerator random,
            boolean freshSprint) {

        if (resistanceCancels(victim, profile, random)) {
            return null;
        }
        double taper = profile.rangeReduction().reductionAt(distance(attacker, victim));
        double[] vector = base(victim, attacker.x(), attacker.z(), profile, victimYOverride, random, taper);

        double sprintLevels = attacker.sprinting() ? profile.sprintFactor() : 0.0;
        double enchantLevels = attacker.knockbackEnchantLevel();
        if (sprintLevels + enchantLevels > 0) {
            boolean wtap = profile.wtapExtra().enabled() && sprintLevels > 0 && freshSprint;
            double sprintHorizontal = wtap ? profile.wtapExtra().horizontal() : profile.extra().horizontal();
            double horizontalBonus =
                    sprintLevels * sprintHorizontal + enchantLevels * profile.extra().horizontal();
            double yawRadians = Math.toRadians(attacker.yaw());
            vector[0] += -Math.sin(yawRadians) * horizontalBonus;
            vector[2] += Math.cos(yawRadians) * horizontalBonus;
            vector[1] += wtap ? profile.wtapExtra().vertical() : profile.extra().vertical();
        }

        return finish(vector, victim, profile);
    }

    /**
     * Base-only knockback away from a source position — the bare legacy
     * {@code knockBack(0.4)} that rod bobbers and thrown projectiles
     * triggered. No bonus levels and no range taper exist on this path.
     */
    public static KnockbackVector computeBase(
            EntityState victim,
            double sourceX,
            double sourceZ,
            KnockbackProfile profile,
            Double victimYOverride,
            RandomGenerator random) {

        if (resistanceCancels(victim, profile, random)) {
            return null;
        }
        return finish(
                base(victim, sourceX, sourceZ, profile, victimYOverride, random, 0.0), victim, profile);
    }

    /** Clamps each axis to the legacy packet limit; bonus additions re-clamp through this. */
    public static KnockbackVector clamp(double x, double y, double z) {
        return new KnockbackVector(
                Math.max(-PACKET_CLAMP, Math.min(PACKET_CLAMP, x)),
                Math.max(-PACKET_CLAMP, Math.min(PACKET_CLAMP, y)),
                Math.max(-PACKET_CLAMP, Math.min(PACKET_CLAMP, z)));
    }

    private static boolean resistanceCancels(
            EntityState victim, KnockbackProfile profile, RandomGenerator random) {
        return profile.resistance() == ResistancePolicy.LEGACY
                && victim.knockbackResistance() > 0.0
                && random.nextDouble() < victim.knockbackResistance();
    }

    private static double distance(EntityState attacker, EntityState victim) {
        double dx = attacker.x() - victim.x();
        double dy = attacker.y() - victim.y();
        double dz = attacker.z() - victim.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double[] base(
            EntityState victim,
            double sourceX,
            double sourceZ,
            KnockbackProfile profile,
            Double victimYOverride,
            RandomGenerator random,
            double pushReduction) {

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
        double push = Math.max(0.0, profile.base().horizontal() - pushReduction);

        // SET assigns the vertical outright — the victim's motion (and with it
        // the latency-compensation vertical hint) is irrelevant by definition.
        double victimVy = victimYOverride != null ? victimYOverride : victim.vy();
        double x = victim.vx() * profile.friction().x() - (deltaX / magnitude) * push;
        double y = profile.verticalMode() == VerticalMode.SET
                ? profile.base().vertical()
                : victimVy * profile.friction().y() + profile.base().vertical();
        double z = victim.vz() * profile.friction().z() - (deltaZ / magnitude) * push;

        if (profile.limits().limitsHorizontal()) {
            double horizontal = Math.hypot(x, z);
            if (horizontal > profile.limits().horizontal()) {
                double scale = profile.limits().horizontal() / horizontal;
                x *= scale;
                z *= scale;
            }
        }

        if (profile.limits().limitsVertical() && y > profile.limits().vertical()) {
            y = profile.limits().vertical();
        }
        return new double[] {x, y, z};
    }

    private static KnockbackVector finish(double[] vector, EntityState victim, KnockbackProfile profile) {
        if (!victim.grounded()) {
            vector[0] *= profile.air().horizontal();
            vector[2] *= profile.air().horizontal();
            vector[1] *= profile.air().vertical();
        }

        applyAdd(vector, profile.add());

        if (vector[1] < profile.limits().verticalMin()) {
            vector[1] = profile.limits().verticalMin();
        }

        if (profile.resistance() == ResistancePolicy.SCALING && victim.knockbackResistance() > 0.0) {
            double survives = 1.0 - victim.knockbackResistance();
            vector[0] *= survives;
            vector[2] *= survives;
        }
        return clamp(vector[0], vector[1], vector[2]);
    }

    /**
     * WindSpigot's add offsets: the horizontal addition distributes across
     * X/Z by each axis's share of the motion and matches its sign, the
     * vertical addition matches the vertical sign — a zero axis (or a zero
     * vector) receives nothing, so the offset can never reverse a direction.
     */
    private static void applyAdd(double[] vector, KnockbackProfile.Push add) {
        if (add.horizontal() != 0.0) {
            double absX = Math.abs(vector[0]);
            double absZ = Math.abs(vector[2]);
            double total = absX + absZ;
            if (total > 1.0e-9) {
                vector[0] += Math.signum(vector[0]) * add.horizontal() * (absX / total);
                vector[2] += Math.signum(vector[2]) * add.horizontal() * (absZ / total);
            }
        }
        if (add.vertical() != 0.0) {
            vector[1] += Math.signum(vector[1]) * add.vertical();
        }
    }
}
