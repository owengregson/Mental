package me.vexmc.mental.module.compensation;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * Distance from a player's feet to the nearest block below, via four corner
 * raytraces (adapted from KnockbackSync's ground probe). Anything beyond five
 * blocks is "clearly airborne" and the prediction is refused. Must run on the
 * player's owning thread.
 */
final class GroundProbe {

    private static final double MAX_DISTANCE = 5.0;
    private static final double CORNER_INSET = 0.01;
    private static final Vector DOWN = new Vector(0, -1, 0);

    private GroundProbe() {}

    static double distanceToGround(@NotNull Player player) {
        World world = player.getWorld();
        BoundingBox box = player.getBoundingBox();
        double feetY = player.getLocation().getY();

        double[][] corners = {
                {box.getMinX() + CORNER_INSET, box.getMinZ() + CORNER_INSET},
                {box.getMinX() + CORNER_INSET, box.getMaxZ() - CORNER_INSET},
                {box.getMaxX() - CORNER_INSET, box.getMinZ() + CORNER_INSET},
                {box.getMaxX() - CORNER_INSET, box.getMaxZ() - CORNER_INSET},
        };

        double best = MAX_DISTANCE;
        for (double[] corner : corners) {
            Location origin = new Location(world, corner[0], feetY, corner[1]);
            RayTraceResult hit = world.rayTraceBlocks(origin, DOWN, MAX_DISTANCE,
                    FluidCollisionMode.NEVER, true);
            if (hit == null || hit.getHitBlock() == null) {
                continue;
            }
            double distance = feetY - hit.getHitBlock().getY();
            if (distance < best) {
                best = distance;
            }
        }
        return best - 1.0;
    }
}
