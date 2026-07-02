package me.vexmc.mental.v5.session;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * The victim's distance to the ground below — the owning-thread kinematic input
 * the compensation query reads (spec §5). <b>Clean-room</b>: derived from the
 * geometry alone, not the GPL-lineage {@code GroundProbe} it replaces.
 *
 * <p>Geometry: the player's collision box is 0.6 blocks wide, so its footprint
 * spans ±0.3 from the feet in x and z. Four rays are cast straight down, one
 * from each corner <em>inset 0.01</em> toward the centre (±0.29) so a ray on a
 * block edge does not miss the block it is standing on. Each ray finds the top
 * surface of the first solid block within {@link #MAX_DISTANCE} (5.0) below the
 * feet; the result is the <em>minimum</em> of the four corner distances — the
 * nearest ground under any part of the box. Owning-thread only (it reads live
 * blocks).</p>
 */
public final class GroundDistance {

    /** No ground is looked for beyond this many blocks below the feet. */
    public static final double MAX_DISTANCE = 5.0;

    /** Half the 0.6-wide collision box. */
    static final double BOX_HALF_WIDTH = 0.3;

    /** Corner inset toward the centre so an edge ray still hits its own block. */
    static final double CORNER_INSET = 0.01;

    private GroundDistance() {}

    /**
     * One downward ray sampler: given a corner's {@code (x, z)}, return the
     * distance from the feet down to the first solid block's top surface, or
     * {@link #MAX_DISTANCE} (or more) when none is within range. Stubbable, so
     * the corner/inset geometry is unit-testable without a live world.
     */
    @FunctionalInterface
    public interface Column {
        double distanceToGroundBelow(double x, double z);
    }

    /**
     * The pure geometry: the minimum distance-to-ground across the four inset
     * corners of the box centred at {@code (footX, footZ)}, capped at
     * {@link #MAX_DISTANCE}.
     */
    public static double measure(double footX, double footZ, Column column) {
        double offset = BOX_HALF_WIDTH - CORNER_INSET; // 0.29
        double min = MAX_DISTANCE;
        double[] deltas = {-offset, offset};
        for (double dx : deltas) {
            for (double dz : deltas) {
                min = Math.min(min, column.distanceToGroundBelow(footX + dx, footZ + dz));
            }
        }
        return Math.min(min, MAX_DISTANCE);
    }

    /** Production entry: the four corner rays scan the live world below the feet. */
    public static double measure(Location feet) {
        World world = feet.getWorld();
        double footY = feet.getY();
        return measure(feet.getX(), feet.getZ(), (x, z) -> scanDown(world, x, footY, z));
    }

    private static double scanDown(World world, double x, double footY, double z) {
        int blockX = floor(x);
        int blockZ = floor(z);
        int top = floor(footY);
        int bottom = floor(footY - MAX_DISTANCE);
        for (int blockY = top; blockY >= bottom; blockY--) {
            Block block = world.getBlockAt(blockX, blockY, blockZ);
            if (block.isPassable()) {
                continue;
            }
            double surface = block.getBoundingBox().getMaxY();
            double distance = footY - surface;
            return distance < 0.0 ? 0.0 : distance;
        }
        return MAX_DISTANCE;
    }

    private static int floor(double value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }
}
