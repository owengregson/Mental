package me.vexmc.strikesync.module.knockback;

import me.vexmc.strikesync.config.KnockbackSettings;
import me.vexmc.strikesync.module.hitreg.PlayerStateCache;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Pure 1.8.x-style knockback math.
 *
 * <h2>Three call surfaces</h2>
 * <ul>
 *   <li>{@link #compute(LivingEntity, LivingEntity, KnockbackSettings)} — main-thread, no override.</li>
 *   <li>{@link #compute(LivingEntity, LivingEntity, KnockbackSettings, Double)} — main-thread, with
 *       a latency-compensation hint for the victim's vy (see {@code LatencyCompensationModule}).</li>
 *   <li>{@link #computeFromCache(PlayerStateCache.Snapshot, PlayerStateCache.Snapshot, KnockbackSettings, Double)}
 *       — pure, takes immutable cached snapshots, callable from any thread (used by the fast-path
 *       async velocity pre-sender).</li>
 * </ul>
 * All three converge on the same private {@link #computeImpl} so the formula is provably identical
 * across paths.
 *
 * <h2>Latency-compensation hook</h2>
 * The {@code victimYOverride} parameter lets the compensation module substitute
 * a "client-expected" vertical velocity for the server's stale value when the
 * victim's ground state was reported with high latency. When {@code null}, the
 * raw victim Y velocity is used.
 *
 * <h2>Formula</h2>
 * <pre>
 *   1. Direction = (attacker.x - victim.x, _, attacker.z - victim.z), normalized.
 *      If the horizontal distance is below 1e-4 we pick a tiny random direction.
 *   2. xVel = vy_x * frictionX - dx/|d| * baseHorizontal
 *      yVel = vy_y * frictionY + baseVertical            (vy_y comes from the override if provided)
 *      zVel = vy_z * frictionZ - dz/|d| * baseHorizontal
 *   3. If horizontal cap is set, rescale (x, z) so |(x, z)| <= limitHorizontal.
 *   4. If sprinting or holding a Knockback enchant, add the "extra" vector.
 *   5. Apply the vertical cap last so neither base nor extras can exceed it.
 *   6. If honoring armor resistance, scale (x, z) by (1 - resistance).
 * </pre>
 */
public final class KnockbackEngine {

    private KnockbackEngine() {}

    /** Convenience overload: no Y override (server's velocity is used). */
    public static KnockbackVector compute(LivingEntity attacker,
                                          LivingEntity victim,
                                          KnockbackSettings s) {
        return compute(attacker, victim, s, null);
    }

    /**
     * @param victimYOverride latency-compensated victim Y velocity, or {@code null}
     *                        to use the server's current velocity.
     */
    public static KnockbackVector compute(LivingEntity attacker,
                                          LivingEntity victim,
                                          KnockbackSettings s,
                                          Double victimYOverride) {
        Vector v = victim.getVelocity();
        return computeImpl(
                attacker.getLocation().getX(), attacker.getLocation().getZ(), attacker.getLocation().getYaw(),
                victim.getLocation().getX(), victim.getLocation().getZ(),
                v.getX(), v.getY(), v.getZ(),
                victimYOverride,
                attacker instanceof Player p && p.isSprinting(),
                readHeldKnockbackLevel(attacker),
                readKnockbackResistance(victim),
                s);
    }

    /**
     * Pure overload usable from any thread (no Bukkit access). Used by the
     * fast hit-registration path to compute the knockback vector for the
     * async velocity pre-send.
     */
    public static KnockbackVector computeFromCache(PlayerStateCache.Snapshot attacker,
                                                   PlayerStateCache.Snapshot victim,
                                                   KnockbackSettings s,
                                                   Double victimYOverride) {
        return computeImpl(
                attacker.x(), attacker.z(), attacker.yaw(),
                victim.x(), victim.z(),
                victim.vx(), victim.vy(), victim.vz(),
                victimYOverride,
                attacker.sprinting(),
                attacker.mainHandKnockbackLevel(),
                victim.knockbackResistance(),
                s);
    }

    /* ---------------------------------------------------------------------- */
    /*  Pure formula                                                           */
    /* ---------------------------------------------------------------------- */

    private static KnockbackVector computeImpl(
            double ax, double az, float ayaw,
            double vx, double vz,
            double vvx, double vvy, double vvz,
            Double victimYOverride,
            boolean attackerSprinting,
            int knockbackEnchantLevel,
            double victimKnockbackResistance,
            KnockbackSettings s) {

        // 1) Horizontal direction.
        double deltaX = ax - vx;
        double deltaZ = az - vz;
        if (deltaX * deltaX + deltaZ * deltaZ < 1.0E-4D) {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            deltaX = (rng.nextDouble() - rng.nextDouble()) * 0.01D;
            deltaZ = (rng.nextDouble() - rng.nextDouble()) * 0.01D;
            if (deltaX * deltaX + deltaZ * deltaZ < 1.0E-8D) {
                deltaX = 0.01D;
            }
        }
        double magnitude = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

        // 2) Base velocity, with optional Y override from latency compensation.
        double victimVy = victimYOverride != null ? victimYOverride : vvy;
        double x = vvx * s.frictionX() - (deltaX / magnitude) * s.baseHorizontal();
        double y = victimVy * s.frictionY() + s.baseVertical();
        double z = vvz * s.frictionZ() - (deltaZ / magnitude) * s.baseHorizontal();

        // 3) Horizontal cap.
        if (s.limitsHorizontal()) {
            double horizontal = Math.hypot(x, z);
            if (horizontal > s.limitHorizontal()) {
                double scale = s.limitHorizontal() / horizontal;
                x *= scale;
                z *= scale;
            }
        }

        // 4) Sprint + Knockback enchant bonus.
        double bonusLevel = (attackerSprinting ? s.sprintFactor() : 0.0D) + knockbackEnchantLevel;
        if (bonusLevel > 0) {
            double yawRad = Math.toRadians(ayaw);
            x += -Math.sin(yawRad) * bonusLevel * s.extraHorizontal();
            z += Math.cos(yawRad) * bonusLevel * s.extraHorizontal();
            y += s.extraVertical();
        }

        // 5) Vertical cap, last so extras can't push past it.
        if (s.limitsVertical() && y > s.limitVertical()) {
            y = s.limitVertical();
        }

        // 6) Horizontal armor knockback resistance.
        if (s.honorArmorResistance() && victimKnockbackResistance > 0.0D) {
            double survives = 1.0D - victimKnockbackResistance;
            x *= survives;
            z *= survives;
        }

        return new KnockbackVector(x, y, z);
    }

    /* ---------------------------------------------------------------------- */
    /*  Bukkit-side helpers                                                    */
    /* ---------------------------------------------------------------------- */

    private static double readKnockbackResistance(LivingEntity entity) {
        AttributeInstance attr = entity.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        if (attr == null) return 0.0D;
        return Math.max(0.0D, Math.min(1.0D, attr.getValue()));
    }

    private static int readHeldKnockbackLevel(LivingEntity attacker) {
        EntityEquipment eq = attacker.getEquipment();
        if (eq == null) return 0;
        ItemStack weapon = eq.getItemInMainHand().getType() == Material.AIR
                ? eq.getItemInOffHand()
                : eq.getItemInMainHand();
        if (weapon == null || weapon.getType() == Material.AIR) return 0;
        return weapon.getEnchantmentLevel(Enchantment.KNOCKBACK);
    }
}
