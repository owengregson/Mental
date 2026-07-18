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
 * Fired on the victim's owning region thread the instant an active combo held
 * against {@code victim} ends (combo-hold §3.1). A pure notification (not
 * cancellable). Balanced with {@link ComboStartEvent}: <b>exactly one</b> end
 * follows each start, for every reason — including DISABLED on both module
 * toggle-off and plugin disable. The {@link #getReason()} names why the pocket
 * broke.
 *
 * <p>An attacker-switch restart fires this terminal for the old sequence
 * <b>before</b> the new opening event, in that order, same thread, same tick. A
 * developing chain that never activated ends via {@link ComboChainAbortEvent}
 * instead, never here. No combo state survives a server restart.</p>
 *
 * <p>The {@code attacker} entity is the party that had held the combo, resolved
 * best-effort (may be null off-region on Folia); {@link #getAttackerId()} and
 * {@link #getVictim()} are always present.</p>
 */
public final class ComboEndEvent extends Event {

    /** Why an active combo ended — the public mirror of the kernel's own reasons. */
    public enum Reason {
        /** The inter-hit gap exceeded {@code max-gap-ticks}, or a different attacker took over. */
        EXPIRED,
        /** The victim landed a melee hit of their own. */
        RETALIATION,
        /** A real touchdown — {@code grounded-run-ticks} consecutive grounded ticks. */
        GROUNDED,
        /** Separation exceeded {@code blowout-blocks}. */
        BLOWOUT,
        /** A party retired (logged out / session forgotten). */
        RETIRED,
        /** The combo-hold module was turned off. */
        DISABLED
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player victim;
    private final LivingEntity attacker;
    private final UUID attackerId;
    private final Reason reason;
    private final int hits;
    private final long endedTick;

    public ComboEndEvent(@NotNull Player victim, @Nullable LivingEntity attacker,
                         @NotNull UUID attackerId, @NotNull Reason reason, int hits, long endedTick) {
        this.victim = victim;
        this.attacker = attacker;
        this.attackerId = attackerId;
        this.reason = reason;
        this.hits = hits;
        this.endedTick = endedTick;
    }

    /**
     * Constructor kept for binary compatibility; events Mental fires always
     * carry a non-null attacker id (derived here from the entity when present),
     * the final chain length, and a real ended tick.
     *
     * @deprecated use {@link #ComboEndEvent(Player, LivingEntity, UUID, Reason, int, long)}.
     */
    @Deprecated
    public ComboEndEvent(@NotNull Player victim, @Nullable LivingEntity attacker, @NotNull Reason reason) {
        this(victim, attacker, attacker != null ? attacker.getUniqueId() : null, reason, 0, MentalCombat.NO_TICK);
    }

    /** The player who was being combo'd. */
    public @NotNull Player getVictim() {
        return victim;
    }

    /** The attacker who had held the combo, or null when the entity could not be resolved. */
    public @Nullable LivingEntity getAttacker() {
        return attacker;
    }

    /** The attacker's UUID — always present on events Mental fires. */
    public @NotNull UUID getAttackerId() {
        return attackerId;
    }

    /** Why the combo ended. */
    public @NotNull Reason getReason() {
        return reason;
    }

    /** The final chain length at the moment the combo ended. */
    public int getHits() {
        return hits;
    }

    /** The Mental-clock tick (see {@link MentalCombat}) the combo ended on. */
    public long getEndedTick() {
        return endedTick;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
