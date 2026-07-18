package me.vexmc.mental.api.event;

import java.util.UUID;
import me.vexmc.mental.api.MentalCombat;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Fired on the victim's owning region thread for every <b>confirmed-shipped
 * qualifying melee knock that advances a not-yet-active chain</b> (hits
 * {@code 1..min-hits−1}) — the developing window {@link ComboStartEvent} cannot
 * see. A pure notification (not cancellable), so a consumer can arm its own
 * deferral for the developing window exactly, before the combo ever promotes.
 *
 * <p>"Qualifying knock" is precise: a melee knock Mental <em>confirmed shipped</em>
 * on the victim's {@code PlayerVelocityEvent} — a foreign plugin that cancels
 * that velocity event never advances a chain (D1), and a natively blocked melee
 * hit DOES qualify, because it re-delivers through the same authoritative
 * velocity seam (D2).</p>
 *
 * <p>{@code getHits() == 1} is the chain opening; each later value is a
 * developing advance. Every {@code ComboChainEvent(hits == 1)} sequence
 * terminates in exactly one of {@link ComboStartEvent} (promotion) or
 * {@link ComboChainAbortEvent}. The {@code attacker} entity is best-effort and
 * may be null (off-region on Folia); {@link #getAttackerId()} is always present.</p>
 */
public final class ComboChainEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player victim;
    private final LivingEntity attacker;
    private final UUID attackerId;
    private final int hits;
    private final long gapDeadlineTick;

    public ComboChainEvent(@NotNull Player victim, @Nullable LivingEntity attacker,
                           @NotNull UUID attackerId, int hits, long gapDeadlineTick) {
        this.victim = victim;
        this.attacker = attacker;
        this.attackerId = attackerId;
        this.hits = hits;
        this.gapDeadlineTick = gapDeadlineTick;
    }

    /** The player whose developing chain advanced. */
    public @NotNull Player getVictim() {
        return victim;
    }

    /** The attacker entity, best-effort — null when it could not be resolved. */
    public @Nullable LivingEntity getAttacker() {
        return attacker;
    }

    /** The attacker's UUID — always present. */
    public @NotNull UUID getAttackerId() {
        return attackerId;
    }

    /** The developing chain length; {@code 1} is the chain opening. */
    public int getHits() {
        return hits;
    }

    /**
     * The Mental-clock tick (see {@link MentalCombat}) by which the next
     * qualifying knock must ship or this chain dies. Compute "ticks remaining"
     * only as a delta against {@link MentalCombat#currentTick()}.
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
