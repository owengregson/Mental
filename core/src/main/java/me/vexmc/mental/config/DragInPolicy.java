package me.vexmc.mental.config;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** Which hooked entities have vanilla's reel-in pull suppressed. */
public enum DragInPolicy {
    ALL,
    PLAYERS,
    MOBS,
    NONE;

    public boolean cancels(@NotNull Entity hooked) {
        return switch (this) {
            case ALL -> true;
            case PLAYERS -> hooked instanceof Player;
            case MOBS -> !(hooked instanceof Player);
            case NONE -> false;
        };
    }
}
