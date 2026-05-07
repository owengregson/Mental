package me.vexmc.strikesync.module.compensation;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Estimates the vertical distance from a player's feet to the nearest block
 * directly below them, using a 4-corner raytrace.
 *
 * <p>
 * Adapted from KnockbackSync's {@code PlayerData.getDistanceToGround}, but
 * uses Paper's first-class {@link World#rayTraceBlocks} so we don't have to
 * pull in PacketEvents for its world abstraction. The 5-block ceiling matches
 * KnockbackSync's behaviour: anything farther than 5 blocks is "clearly in the
 * air" and we punt on the prediction.
 */
final class GroundProbe {

	private static final double MAX_DISTANCE = 5.0D;
	private static final double CORNER_INSET = 0.01D;
	private static final Vector DOWN = new Vector(0, -1, 0);

	private GroundProbe() {
	}

	/**
	 * @return distance from feet to nearest block, or {@link #MAX_DISTANCE} when
	 *         nothing is in range.
	 */
	static double distanceToGround(Player player) {
		World world = player.getWorld();
		BoundingBox box = player.getBoundingBox();
		double feetY = player.getLocation().getY();

		double[][] corners = {
				{ box.getMinX() + CORNER_INSET, box.getMinZ() + CORNER_INSET },
				{ box.getMinX() + CORNER_INSET, box.getMaxZ() - CORNER_INSET },
				{ box.getMaxX() - CORNER_INSET, box.getMinZ() + CORNER_INSET },
				{ box.getMaxX() - CORNER_INSET, box.getMaxZ() - CORNER_INSET },
		};

		double best = MAX_DISTANCE;
		for (double[] corner : corners) {
			Location origin = new Location(world, corner[0], feetY, corner[1]);
			RayTraceResult hit = world.rayTraceBlocks(origin, DOWN, MAX_DISTANCE,
					FluidCollisionMode.NEVER, true);
			if (hit == null || hit.getHitBlock() == null)
				continue;
			double dy = feetY - hit.getHitBlock().getY();
			if (dy < best)
				best = dy;
		}
		return best - 1.0D;
	}
}
