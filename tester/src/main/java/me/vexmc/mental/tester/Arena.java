package me.vexmc.mental.tester;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

/**
 * A deterministic floating stone platform, far from spawn mobs and terrain.
 * Fake players have no client physics, so a flat known surface keeps every
 * geometric expectation exact.
 */
public final class Arena {

    private static final int BASE_X = 100;
    private static final int BASE_Y = 150;
    private static final int BASE_Z = 100;
    private static final int SIZE = 11;
    private static final int RUNWAY_LENGTH = 34;

    private Arena() {}

    /** Builds (idempotently) and returns the platform centre. Main thread only. */
    public static @NotNull Location prepare(@NotNull World world) {
        return build(world, SIZE, SIZE, SIZE / 2.0, SIZE / 2.0);
    }

    /**
     * A long strip along +z for trajectory tests: knocked players travel
     * many blocks before settling, and the floor must be there when they
     * land. Returns a start point near the low-z end; knock toward +z.
     */
    public static @NotNull Location prepareRunway(@NotNull World world) {
        return build(world, SIZE, RUNWAY_LENGTH, SIZE / 2.0, 3.5);
    }

    /** The runway floor's top surface — the y a settled player rests on. */
    public static double floorY() {
        return BASE_Y + 1.0;
    }

    private static Location build(World world, int sizeX, int sizeZ, double centreDx, double centreDz) {
        world.getChunkAt(BASE_X >> 4, BASE_Z >> 4).load();
        world.getChunkAt((BASE_X + sizeX) >> 4, (BASE_Z + sizeZ) >> 4).load();
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                world.getBlockAt(BASE_X + x, BASE_Y, BASE_Z + z).setType(Material.STONE, false);
                for (int y = 1; y <= 4; y++) {
                    world.getBlockAt(BASE_X + x, BASE_Y + y, BASE_Z + z).setType(Material.AIR, false);
                }
            }
        }
        return new Location(world, BASE_X + centreDx, BASE_Y + 1.0, BASE_Z + centreDz);
    }

    public static @NotNull Location offset(@NotNull Location centre, double dx, double dz) {
        return centre.clone().add(dx, 0, dz);
    }
}
