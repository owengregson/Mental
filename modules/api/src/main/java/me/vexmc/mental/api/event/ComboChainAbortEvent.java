package me.vexmc.mental.api.event;

import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired on the victim's owning region thread when a DEVELOPING chain dies
 * <b>without ever activating</b> — gap expiry, an attacker switch abandoning the
 * old developing chain, victim retaliation, retire, or module disable. A pure
 * notification (not cancellable): it is the balanced terminal for a
 * {@link ComboChainEvent} sequence that never reached {@link ComboStartEvent}.
 *
 * <p>An active combo never reports here — its end is {@link ComboEndEvent}. The
 * two vocabularies are deliberately distinct: only a developing chain can die by
 * attacker switch ({@link Reason#SWITCHED}), a cause an active end never names.</p>
 */
public final class ComboChainAbortEvent extends Event {

    /** Why a developing (pre-activation) chain died without activating. */
    public enum Reason {
        /** The inter-hit gap lapsed before the next qualifying knock shipped. */
        EXPIRED,
        /**
         * A different attacker's knock abandoned this developing chain.
         * SWITCHED wins whenever the terminating hit's attacker differs from
         * the chain's — even if the gap had also already lapsed — so the "new
         * attacker arrives after the old gap already lapsed" hit reports a
         * stable value.
         */
        SWITCHED,
        /** The victim landed a melee hit of their own. */
        RETALIATION,
        /** A party retired (logged out / session forgotten). */
        RETIRED,
        /** The combo module was turned off. */
        DISABLED
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player victim;
    private final UUID attackerId;
    private final int hits;
    private final Reason reason;

    public ComboChainAbortEvent(@NotNull Player victim, @NotNull UUID attackerId,
                                int hits, @NotNull Reason reason) {
        this.victim = victim;
        this.attackerId = attackerId;
        this.hits = hits;
        this.reason = reason;
    }

    /** The player whose developing chain was abandoned. */
    public @NotNull Player getVictim() {
        return victim;
    }

    /** The attacker's UUID — always present. */
    public @NotNull UUID getAttackerId() {
        return attackerId;
    }

    /** The final developing chain length at the moment it died. */
    public int getHits() {
        return hits;
    }

    /** Why the developing chain died. */
    public @NotNull Reason getReason() {
        return reason;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
