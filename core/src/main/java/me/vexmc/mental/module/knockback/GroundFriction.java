package me.vexmc.mental.module.knockback;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * The block-under-feet slipperiness the era ground physics multiplied into
 * the horizontal drag ({@code slipperiness × 0.91}). The era table is tiny
 * and closed — default 0.6 with ice 0.98 and slime 0.8 — so it is pinned by
 * NAME here rather than read from a version-roulette API: the values are
 * era constants, not host-server state (blue ice post-dates the era; its
 * real 0.989 is used so modern arenas behave sanely under era profiles).
 *
 * <p>Measured on real 1.7.10: a packed-ice lane ships hit 1 at
 * 0.4 × (0.98 × 0.91) = 0.3567 and combo residuals survive between hits —
 * ice nearly doubles era knockback distances (compendium §5b).</p>
 */
public final class GroundFriction {

    private GroundFriction() {}

    /** Slipperiness of the block under the player's feet; owning-thread only. */
    public static double under(@NotNull Player player) {
        Location location = player.getLocation();
        Material material = player.getWorld().getBlockAt(
                location.getBlockX(), location.getBlockY() - 1, location.getBlockZ()).getType();
        return of(material);
    }

    public static double of(@NotNull Material material) {
        return switch (material.name()) {
            case "ICE", "PACKED_ICE", "FROSTED_ICE" -> 0.98;
            case "BLUE_ICE" -> 0.989;
            case "SLIME_BLOCK" -> 0.8;
            default -> VictimMotion.DEFAULT_SLIPPERINESS;
        };
    }
}
