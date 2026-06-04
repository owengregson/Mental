package me.vexmc.mental.api.event;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Fired on the victim's owning thread immediately before Mental applies a
 * computed knockback vector. Mutate {@link #velocity(Vector)} to adjust the
 * knockback, or cancel to let vanilla velocity stand.
 */
public final class KnockbackApplyEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player victim;
    private final LivingEntity attacker;
    private Vector velocity;
    private boolean cancelled;

    public KnockbackApplyEvent(@NotNull Player victim, @Nullable LivingEntity attacker, @NotNull Vector velocity) {
        this.victim = victim;
        this.attacker = attacker;
        this.velocity = velocity;
    }

    public @NotNull Player getVictim() {
        return victim;
    }

    public @Nullable LivingEntity getAttacker() {
        return attacker;
    }

    public @NotNull Vector velocity() {
        return velocity.clone();
    }

    public void velocity(@NotNull Vector velocity) {
        this.velocity = velocity.clone();
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
