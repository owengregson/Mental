package me.vexmc.mental.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Fired after a player's knockback-profile <em>override</em> changes — set,
 * replaced, or cleared — through the API or {@code /mental kb}. A null name
 * means "no override": the player falls back to their world's profile or the
 * server default. Practice cores listen here to mirror kit/arena profile
 * swaps.
 *
 * <p>Fired synchronously from the caller's thread; change profiles from the
 * player's owning thread.</p>
 */
public final class PlayerKnockbackProfileChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String previousProfile;
    private final String newProfile;

    public PlayerKnockbackProfileChangeEvent(
            @NotNull Player player, @Nullable String previousProfile, @Nullable String newProfile) {
        this.player = player;
        this.previousProfile = previousProfile;
        this.newProfile = newProfile;
    }

    public @NotNull Player getPlayer() {
        return player;
    }

    /** The previous override, or null when none was set. */
    public @Nullable String getPreviousProfile() {
        return previousProfile;
    }

    /** The new override, or null when it was cleared. */
    public @Nullable String getNewProfile() {
        return newProfile;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
