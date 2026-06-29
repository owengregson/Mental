package me.vexmc.mental.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired after the server-wide knockback profile changes — through the
 * management GUI or {@code MentalApi.setKnockbackProfile}. Knockback in Mental
 * is global: one profile governs every player (subject only to the optional
 * per-world map in knockback.yml). Practice cores listen here to mirror the
 * active feel.
 *
 * <p>Fires on the thread that performed the change (the main thread on
 * non-Folia servers, the global region thread on Folia), synchronously, after
 * the configuration snapshot has been swapped — so a listener reading the
 * active profile sees the new value.</p>
 */
public final class KnockbackProfileChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String previousProfile;
    private final String newProfile;

    public KnockbackProfileChangeEvent(@NotNull String previousProfile, @NotNull String newProfile) {
        this.previousProfile = previousProfile;
        this.newProfile = newProfile;
    }

    /** The profile name that was active before this change. */
    public @NotNull String getPreviousProfile() {
        return previousProfile;
    }

    /** The profile name now active server-wide. */
    public @NotNull String getNewProfile() {
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
