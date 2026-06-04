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

    private Arena() {}

    /** Builds (idempotently) and returns the platform centre. Main thread only. */
    public static @NotNull Location prepare(@NotNull World world) {
        world.getChunkAt(BASE_X >> 4, BASE_Z >> 4).load();
        world.getChunkAt((BASE_X + SIZE) >> 4, (BASE_Z + SIZE) >> 4).load();
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                world.getBlockAt(BASE_X + x, BASE_Y, BASE_Z + z).setType(Material.STONE, false);
                for (int y = 1; y <= 4; y++) {
                    world.getBlockAt(BASE_X + x, BASE_Y + y, BASE_Z + z).setType(Material.AIR, false);
                }
            }
        }
        return new Location(world, BASE_X + SIZE / 2.0, BASE_Y + 1.0, BASE_Z + SIZE / 2.0);
    }

    public static @NotNull Location offset(@NotNull Location centre, double dx, double dz) {
        return centre.clone().add(dx, 0, dz);
    }
}
