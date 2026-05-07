package me.vexmc.strikesync.config;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Immutable view over the {@code knockback} config section.
 *
 * <p>
 * Defaults reproduce the well-known Minecraft 1.8.8 PvP feel.
 *
 * @param enabled              master switch for the knockback module.
 * @param baseHorizontal       base horizontal knockback magnitude.
 * @param baseVertical         base vertical knockback added on hit.
 * @param extraHorizontal      per-level horizontal bonus from sprint and the
 *                             Knockback enchantment.
 * @param extraVertical        vertical bonus added when an extra-knockback
 *                             condition applies.
 * @param limitVertical        hard upper bound on Y component; {@code <= 0}
 *                             disables.
 * @param limitHorizontal      hard upper bound on horizontal magnitude;
 *                             {@code <= 0} disables.
 * @param frictionX            retention of the victim's pre-hit X velocity.
 * @param frictionY            retention of the victim's pre-hit Y velocity.
 * @param frictionZ            retention of the victim's pre-hit Z velocity.
 * @param sprintFactor         extra-knockback level added when the attacker is
 *                             sprinting.
 * @param honorArmorResistance whether to honor the victim's
 *                             KNOCKBACK_RESISTANCE attribute.
 */
public record KnockbackSettings(
		boolean enabled,
		double baseHorizontal,
		double baseVertical,
		double extraHorizontal,
		double extraVertical,
		double limitVertical,
		double limitHorizontal,
		double frictionX,
		double frictionY,
		double frictionZ,
		double sprintFactor,
		boolean honorArmorResistance) {

	public static KnockbackSettings from(ConfigurationSection s) {
		return new KnockbackSettings(
				s.getBoolean("enabled", true),
				s.getDouble("base.horizontal", 0.4D),
				s.getDouble("base.vertical", 0.4D),
				s.getDouble("extra.horizontal", 0.5D),
				s.getDouble("extra.vertical", 0.1D),
				s.getDouble("limits.vertical", 0.4D),
				s.getDouble("limits.horizontal", -1.0D),
				s.getDouble("friction.x", 0.5D),
				s.getDouble("friction.y", 0.5D),
				s.getDouble("friction.z", 0.5D),
				s.getDouble("modifiers.sprint", 1.0D),
				s.getBoolean("modifiers.armor-resistance", false));
	}

	public boolean limitsHorizontal() {
		return limitHorizontal > 0.0D;
	}

	public boolean limitsVertical() {
		return limitVertical > 0.0D;
	}
}
