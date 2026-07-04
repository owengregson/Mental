package me.vexmc.mental.api.event;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Fired on the victim's owning region thread the instant the combo-hold detector
 * recognises an active combo held against {@code victim} — the third qualifying
 * melee hit from one attacker within the cadence window (combo-hold §3.1). A
 * pure notification (not cancellable): the servo is a knockback shaping the
 * {@code DeliveryDesk} already owns, so there is nothing here for a listener to
 * veto. Handy as a free scoreboard / integration signal (who is holding a combo,
 * how long).
 *
 * <p>Balanced with {@link ComboEndEvent}: every start is followed by exactly one
 * end (retaliation, grounding, gap expiry, blowout, retire, or module disable).
 * The {@code attacker} is resolved best-effort and may be null when the entity
 * cannot be read (e.g. off-region on Folia); the {@code victim} is always
 * present.</p>
 */
public final class ComboStartEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player victim;
    private final LivingEntity attacker;
    private final int hits;

    public ComboStartEvent(@NotNull Player victim, @Nullable LivingEntity attacker, int hits) {
        this.victim = victim;
        this.attacker = attacker;
        this.hits = hits;
    }

    /** The player being combo'd. */
    public @NotNull Player getVictim() {
        return victim;
    }

    /** The attacker holding the combo, or null when the entity could not be resolved. */
    public @Nullable LivingEntity getAttacker() {
        return attacker;
    }

    /** The chain length at the moment the combo went active (at least the configured {@code min-hits}). */
    public int getHits() {
        return hits;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
