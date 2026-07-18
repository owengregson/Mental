package me.vexmc.mental.tester;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
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
    private static final int SIZE = 12;
    // Flight room along +z for FULL-STAMP knocks: a sprint knock flies
    // ~4.95 blocks (the 11-block square of the decayed-wire era let victims
    // sail off the +z edge into a 150-block drop, whose repeated hurt
    // events polluted the captors). The ACTORS must stay inside the
    // original chunk (x,z < 112): 1.20.6 does not tick clientless players
    // in the neighbouring chunk, and an unticked victim never settles onto
    // the floor — it reads an airborne zero baseline instead of the
    // grounded −0.0784 equilibrium.
    private static final int PLATFORM_LENGTH = 21;
    private static final int RUNWAY_LENGTH = 34;

    private Arena() {}

    /**
     * Builds (idempotently) and returns the scenario anchor. Main thread
     * only. The anchor sits near the low-z end — knocks travel +z — so the
     * actors share the old single-chunk placement while the floor extends
     * far enough that full-stamp flights land on stone.
     */
    public static @NotNull Location prepare(@NotNull World world) {
        return build(world, SIZE, PLATFORM_LENGTH, SIZE / 2.0, 3.5);
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
        // The run worlds are normal night-cycling worlds and the platform is
        // dark spawnable stone: hostile mobs spawn ON it, wander over and
        // punch test players mid-scenario — a zombie's 2.5 damage and its
        // full-strength 0.4 knock at an arbitrary bearing read exactly like
        // phantom velocity events in the captors. Spawning stays off and
        // anything hostile already nearby is purged each prepare.
        disableMobSpawning(world);
        for (Entity entity : world.getEntities()) {
            if (entity instanceof Monster
                    && Math.abs(entity.getLocation().getX() - BASE_X) < 64
                    && Math.abs(entity.getLocation().getZ() - BASE_Z) < 64) {
                entity.remove();
            }
        }
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

    /**
     * Turns mob spawning off in a version-neutral way. The typed
     * {@code World#setGameRule(GameRule, T)} is Bukkit 1.13+; referencing
     * {@code org.bukkit.GameRule} directly throws {@link NoClassDefFoundError} on
     * 1.9–1.12, so the typed call is reached only reflectively (never linked on a
     * pre-1.13 server), with the deprecated String gamerule API as the pre-1.13
     * fallback. Modern behaviour is unchanged — the typed rule is still applied.
     */
    private static void disableMobSpawning(World world) {
        try {
            Class<?> gameRuleClass = Class.forName("org.bukkit.GameRule");
            Object rule = gameRuleClass.getField("DO_MOB_SPAWNING").get(null);
            World.class.getMethod("setGameRule", gameRuleClass, Object.class)
                    .invoke(world, rule, Boolean.FALSE);
        } catch (ReflectiveOperationException pre1_13) {
            setGameRuleValueLegacy(world);
        }
    }

    @SuppressWarnings("deprecation") // the String gamerule API is the only one 1.9–1.12 has
    private static void setGameRuleValueLegacy(World world) {
        world.setGameRuleValue("doMobSpawning", "false");
    }
}
