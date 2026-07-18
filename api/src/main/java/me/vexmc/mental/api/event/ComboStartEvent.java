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
 * Fired on the victim's owning region thread the instant the combo detector
 * recognises an active combo held against {@code victim} — the configured
 * min-hits qualifying melee knock (default: the second) from one attacker within
 * the cadence window (combo-hold §3.1). A pure notification (not cancellable):
 * the servo is a knockback shaping the {@code DeliveryDesk} already owns, so
 * there is nothing here for a listener to veto. Handy as a free scoreboard /
 * integration signal (who is holding a combo, how long).
 *
 * <p>Balanced with {@link ComboEndEvent}: every start is followed by <b>exactly
 * one</b> end (retaliation, grounding, gap expiry, blowout, retire, or module
 * disable — DISABLED fires on module toggle-off and on plugin disable too). The
 * developing window before this event is announced by {@link ComboChainEvent};
 * an attacker-switch restart fires the old sequence's terminal <b>before</b> this
 * opening event, same thread, same tick. No combo state survives a server
 * restart.</p>
 *
 * <p>The {@code attacker} entity is resolved best-effort and may be null when it
 * cannot be read (e.g. off-region on Folia); {@link #getAttackerId()} and
 * {@link #getVictim()} are always present.</p>
 */
public final class ComboStartEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player victim;
    private final LivingEntity attacker;
    private final UUID attackerId;
    private final int hits;
    private final long startedTick;

    public ComboStartEvent(@NotNull Player victim, @Nullable LivingEntity attacker,
                           @NotNull UUID attackerId, int hits, long startedTick) {
        this.victim = victim;
        this.attacker = attacker;
        this.attackerId = attackerId;
        this.hits = hits;
        this.startedTick = startedTick;
    }

    /**
     * Constructor kept for binary compatibility; events Mental fires always
     * carry a non-null attacker id (derived here from the entity when present)
     * and a real started tick.
     *
     * @deprecated use {@link #ComboStartEvent(Player, LivingEntity, UUID, int, long)}.
     */
    @Deprecated
    public ComboStartEvent(@NotNull Player victim, @Nullable LivingEntity attacker, int hits) {
        this(victim, attacker, attacker != null ? attacker.getUniqueId() : null, hits, MentalCombat.NO_TICK);
    }

    /** The player being combo'd. */
    public @NotNull Player getVictim() {
        return victim;
    }

    /** The attacker holding the combo, or null when the entity could not be resolved. */
    public @Nullable LivingEntity getAttacker() {
        return attacker;
    }

    /** The attacker's UUID — always present on events Mental fires. */
    public @NotNull UUID getAttackerId() {
        return attackerId;
    }

    /** The chain length at the moment the combo went active (at least the configured {@code min-hits}). */
    public int getHits() {
        return hits;
    }

    /** The Mental-clock tick (see {@link MentalCombat}) the combo activated on. */
    public long getStartedTick() {
        return startedTick;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
