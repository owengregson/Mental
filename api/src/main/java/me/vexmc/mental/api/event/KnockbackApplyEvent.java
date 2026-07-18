package me.vexmc.mental.api.event;

import java.util.UUID;
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
 * knockback, {@link #suppress()} to ship zero velocity, or cancel to stand down
 * and let vanilla's own velocity apply.
 *
 * <p><b>Outcome resolution — one last-writer-wins ordering across all three
 * writers</b> ({@link #velocity(Vector)}, {@link #suppress()},
 * {@link #setCancelled(boolean)}):</p>
 * <ul>
 *   <li>{@code velocity(v)} → outcome {@link Outcome#SHIP} with vector v (and
 *       <b>clears a prior cancel</b> — the writer is expressing "ship exactly
 *       this").</li>
 *   <li>{@code suppress()} → outcome {@link Outcome#SUPPRESSED} (zero velocity
 *       ships; clears a prior cancel).</li>
 *   <li>{@code setCancelled(true)} → outcome {@link Outcome#YIELDED} — Mental
 *       stands down and vanilla's own velocity applies (it does NOT mean "no
 *       knockback"); {@code setCancelled(false)} restores SHIP with the last
 *       written (or desk-computed) vector.</li>
 *   <li>{@link #getOutcome()} reflects the current accumulated state at read
 *       time; it is final only when read at MONITOR.</li>
 * </ul>
 *
 * <p>{@link Outcome#YIELDED} knocks do not advance combo chains (Mental did not
 * ship them); {@link Outcome#SUPPRESSED} knocks do (a zero-velocity melee still
 * shipped).</p>
 */
public final class KnockbackApplyEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player victim;
    private final LivingEntity attacker;
    private final UUID attackerId;
    private final Source source;
    private Vector velocity;
    private Outcome outcome = Outcome.SHIP;

    public KnockbackApplyEvent(@NotNull Player victim, @Nullable LivingEntity attacker,
                               @NotNull Vector velocity, @Nullable UUID attackerId, @NotNull Source source) {
        this.victim = victim;
        this.attacker = attacker;
        this.velocity = velocity;
        this.attackerId = attackerId;
        this.source = source;
    }

    /**
     * Constructor kept for binary compatibility; derives the attacker id from
     * the entity when present and defaults the source to {@link Source#OTHER}.
     *
     * @deprecated use {@link #KnockbackApplyEvent(Player, LivingEntity, Vector, UUID, Source)}.
     */
    @Deprecated
    public KnockbackApplyEvent(@NotNull Player victim, @Nullable LivingEntity attacker, @NotNull Vector velocity) {
        this(victim, attacker, velocity, attacker != null ? attacker.getUniqueId() : null, Source.OTHER);
    }

    public @NotNull Player getVictim() {
        return victim;
    }

    public @Nullable LivingEntity getAttacker() {
        return attacker;
    }

    /** The knock's source party, when one exists. */
    public @Nullable UUID getAttackerId() {
        return attackerId;
    }

    /** What kind of hit produced this knock. */
    public @NotNull Source getSource() {
        return source;
    }

    public @NotNull Vector velocity() {
        return velocity.clone();
    }

    public void velocity(@NotNull Vector velocity) {
        this.velocity = velocity.clone();
        this.outcome = Outcome.SHIP;   // "ship exactly this" — clears a prior cancel/suppress
    }

    /** Ship ZERO velocity — explicit intent, distinct from YIELDED (§8). Clears a prior cancel. */
    public void suppress() {
        this.outcome = Outcome.SUPPRESSED;
    }

    @Override
    public boolean isCancelled() {
        return outcome == Outcome.YIELDED;
    }

    @Override
    public void setCancelled(boolean cancel) {
        // true → YIELDED: Mental stands down and vanilla's own velocity applies
        // (NOT "no knockback"); false → restore SHIP with the last written vector.
        this.outcome = cancel ? Outcome.YIELDED : Outcome.SHIP;
    }

    public @NotNull Outcome getOutcome() {
        return outcome;   // current accumulated state; final only when read at MONITOR
    }

    public enum Source { MELEE, ROD, PROJECTILE, OTHER }

    public enum Outcome { SHIP, SUPPRESSED, YIELDED }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
