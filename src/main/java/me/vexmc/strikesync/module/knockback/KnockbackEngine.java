package me.vexmc.strikesync.module.knockback;

import me.vexmc.strikesync.config.KnockbackSettings;
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
 * <h2>Latency-compensation hook</h2>
 * The {@code victimYOverride} parameter lets the compensation module substitute
 * a "client-expected" vertical velocity for the server's stale value when the
 * victim's ground state was reported with high latency. When omitted, the
 * server's actual {@code victim.getVelocity().getY()} is used (the only
 * caller path before the compensation module existed).
 *
 * <p>
 * This composition keeps the engine the single source of truth for "what a
 * 1.8-style hit produces" — the compensator never overwrites the output.
 *
 * <h2>Formula</h2>
 *
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

	private KnockbackEngine() {
	}

	/** Convenience overload: no Y override (server's velocity is used). */
	public static KnockbackVector compute(LivingEntity attacker,
			LivingEntity victim,
			KnockbackSettings s) {
		return compute(attacker, victim, s, null);
	}

	/**
	 * @param victimYOverride latency-compensated victim Y velocity, or {@code null}
	 *                        to use the server's current velocity. Should be the
	 *                        client-side Y (typically 0 when the client thinks the
	 *                        victim is on the ground).
	 */
	public static KnockbackVector compute(LivingEntity attacker,
			LivingEntity victim,
			KnockbackSettings s,
			Double victimYOverride) {
		// 1) Horizontal direction.
		double deltaX = attacker.getLocation().getX() - victim.getLocation().getX();
		double deltaZ = attacker.getLocation().getZ() - victim.getLocation().getZ();
		if (deltaX * deltaX + deltaZ * deltaZ < 1.0E-4D) {
			ThreadLocalRandom rng = ThreadLocalRandom.current();
			deltaX = (rng.nextDouble() - rng.nextDouble()) * 0.01D;
			deltaZ = (rng.nextDouble() - rng.nextDouble()) * 0.01D;
			if (deltaX * deltaX + deltaZ * deltaZ < 1.0E-8D)
				deltaX = 0.01D;
		}
		double magnitude = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

		// 2) Base velocity, with optional Y override from latency compensation.
		Vector v = victim.getVelocity();
		double victimVy = victimYOverride != null ? victimYOverride : v.getY();
		double x = v.getX() * s.frictionX() - (deltaX / magnitude) * s.baseHorizontal();
		double y = victimVy * s.frictionY() + s.baseVertical();
		double z = v.getZ() * s.frictionZ() - (deltaZ / magnitude) * s.baseHorizontal();

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
		double bonusLevel = readBonusLevel(attacker, s);
		if (bonusLevel > 0) {
			double yaw = Math.toRadians(attacker.getLocation().getYaw());
			x += -Math.sin(yaw) * bonusLevel * s.extraHorizontal();
			z += Math.cos(yaw) * bonusLevel * s.extraHorizontal();
			y += s.extraVertical();
		}

		// 5) Vertical cap, last so extras can't push past it.
		if (s.limitsVertical() && y > s.limitVertical()) {
			y = s.limitVertical();
		}

		// 6) Horizontal armor knockback resistance.
		if (s.honorArmorResistance()) {
			AttributeInstance attr = victim.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
			if (attr != null) {
				double resistance = Math.max(0.0D, Math.min(1.0D, attr.getValue()));
				double survives = 1.0D - resistance;
				x *= survives;
				z *= survives;
			}
		}

		return new KnockbackVector(x, y, z);
	}

	private static double readBonusLevel(LivingEntity attacker, KnockbackSettings s) {
		double level = 0.0D;
		if (attacker instanceof Player p && p.isSprinting()) {
			level += s.sprintFactor();
		}
		EntityEquipment eq = attacker.getEquipment();
		if (eq != null) {
			ItemStack weapon = eq.getItemInMainHand().getType() == Material.AIR
					? eq.getItemInOffHand()
					: eq.getItemInMainHand();
			if (weapon != null && weapon.getType() != Material.AIR) {
				level += weapon.getEnchantmentLevel(Enchantment.KNOCKBACK);
			}
		}
		return level;
	}
}
