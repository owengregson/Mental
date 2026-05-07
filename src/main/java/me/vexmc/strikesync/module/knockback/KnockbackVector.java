package me.vexmc.strikesync.module.knockback;

import org.bukkit.util.Vector;

/**
 * Plain immutable result of a knockback calculation.
 *
 * <p>
 * Kept separate from {@link Vector} so we can keep the engine pure (no
 * mutable {@link Vector} aliasing across threads) and so future serialization
 * or unit tests don't have to worry about Bukkit's mutable vector semantics.
 */
public record KnockbackVector(double x, double y, double z) {

	public Vector toBukkit() {
		return new Vector(x, y, z);
	}
}
