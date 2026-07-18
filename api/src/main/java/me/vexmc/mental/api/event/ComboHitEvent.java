package me.vexmc.mental.api.event;

import java.util.UUID;
import me.vexmc.mental.api.MentalCombat;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired on the victim's owning region thread for every chain-advancing
 * confirmed-shipped knock while the combo is <b>ACTIVE</b> (hit
 * {@code min-hits+1} onward). A pure notification (not cancellable): the
 * definitive "this hit continued the combo" signal, replacing any consumer-side
 * melee heuristic.
 *
 * <p>The promotion hit itself (hit {@code == min-hits}) is announced by
 * {@link ComboStartEvent} alone — {@code ComboStartEvent} and {@code ComboHitEvent}
 * never fire for the same knock. "Qualifying knock" is D1/D2-resolved exactly as
 * for {@link ComboChainEvent}: a confirmed-shipped melee knock on the victim's
 * velocity seam, blocked hits included.</p>
 */
public final class ComboHitEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player victim;
    private final UUID attackerId;
    private final int hits;
    private final long tick;
    private final long gapDeadlineTick;

    public ComboHitEvent(@NotNull Player victim, @NotNull UUID attackerId,
                         int hits, long tick, long gapDeadlineTick) {
        this.victim = victim;
        this.attackerId = attackerId;
        this.hits = hits;
        this.tick = tick;
        this.gapDeadlineTick = gapDeadlineTick;
    }

    /** The player being combo'd. */
    public @NotNull Player getVictim() {
        return victim;
    }

    /** The attacker's UUID — always present. */
    public @NotNull UUID getAttackerId() {
        return attackerId;
    }

    /** The chain length after this hit landed. */
    public int getHits() {
        return hits;
    }

    /** The Mental-clock tick (see {@link MentalCombat}) this knock shipped on. */
    public long getTick() {
        return tick;
    }

    /**
     * The Mental-clock tick by which the next qualifying knock must ship or the
     * combo ends. Compute "ticks remaining" only as a delta against
     * {@link MentalCombat#currentTick()}.
     */
    public long getGapDeadlineTick() {
        return gapDeadlineTick;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
