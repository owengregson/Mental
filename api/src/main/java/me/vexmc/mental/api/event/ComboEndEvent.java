package me.vexmc.mental.api.event;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Fired on the victim's owning region thread the instant an active combo held
 * against {@code victim} ends (combo-hold §3.1). A pure notification (not
 * cancellable). Balanced with {@link ComboStartEvent}: exactly one end follows
 * each start. The {@link #getReason()} names why the pocket broke.
 *
 * <p>The {@code attacker} is the party that had held the combo, resolved
 * best-effort (may be null off-region on Folia); the {@code victim} is always
 * present.</p>
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
    private final Reason reason;

    public ComboEndEvent(@NotNull Player victim, @Nullable LivingEntity attacker, @NotNull Reason reason) {
        this.victim = victim;
        this.attacker = attacker;
        this.reason = reason;
    }

    /** The player who was being combo'd. */
    public @NotNull Player getVictim() {
        return victim;
    }

    /** The attacker who had held the combo, or null when the entity could not be resolved. */
    public @Nullable LivingEntity getAttacker() {
        return attacker;
    }

    /** Why the combo ended. */
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
