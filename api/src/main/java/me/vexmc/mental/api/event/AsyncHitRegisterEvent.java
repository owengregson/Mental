package me.vexmc.mental.api.event;

import org.bukkit.entity.Damageable;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired asynchronously when Mental observes a player's attack packet,
 * <strong>before</strong> the server processes the attack.
 *
 * <p>Cancelling drops the hit before vanilla ever sees it. Listeners run on
 * a packet worker thread: <strong>do not mutate Bukkit state here.</strong>
 * Schedule back to the owning thread for anything stateful.</p>
 */
public final class AsyncHitRegisterEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player attacker;
    private final Damageable target;
    private boolean cancelled;

    public AsyncHitRegisterEvent(@NotNull Player attacker, @NotNull Damageable target) {
        super(true);
        this.attacker = attacker;
        this.target = target;
    }

    public @NotNull Player getAttacker() {
        return attacker;
    }

    public @NotNull Damageable getTarget() {
        return target;
    }

    public @NotNull EntityType getEntityType() {
        return target.getType();
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
