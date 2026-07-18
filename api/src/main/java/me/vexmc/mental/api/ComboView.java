package me.vexmc.mental.api;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An immutable snapshot of one victim's combo machine, returned by
 * {@link MentalCombat#comboOn(UUID)}. The three {@link State}s mirror the
 * published lifecycle {@code NONE → DEVELOPING → ACTIVE → NONE}: NONE is no
 * chain at all, DEVELOPING is a chain that has opened but not yet reached
 * {@code min-hits}, ACTIVE is a promoted combo. Never null, never torn.
 */
public interface ComboView {

    /** The victim's current combo state. Never null. */
    @NotNull State state();

    /**
     * The attacker driving the chain, or null iff {@link #state()} is NONE.
     * This is the stable UUID identity; a live entity handle is never part of a
     * query result (Folia entity resolution is region-bound — resolve it
     * yourself on the owning thread if you need it).
     */
    @Nullable UUID attackerId();

    /** The chain length: 0 when NONE, otherwise the count of qualifying knocks landed. */
    int hits();

    /**
     * The tick of the most recent qualifying knock, or
     * {@link MentalCombat#NO_TICK} iff {@link #state()} is NONE. In this
     * surface's clock frame (see {@link MentalCombat}).
     */
    long lastKnockTick();

    /**
     * The tick by which the next qualifying knock must ship or the chain dies,
     * or {@link MentalCombat#NO_TICK} iff {@link #state()} is NONE. Compute
     * "ticks remaining" only as a delta against {@link MentalCombat#currentTick()}.
     */
    long gapDeadlineTick();

    /** The published combo lifecycle states. */
    enum State {
        /** No chain. {@code attackerId()} null; both ticks {@link MentalCombat#NO_TICK}. */
        NONE,
        /** A chain below {@code min-hits}: attacker set, not yet promoted. */
        DEVELOPING,
        /** A promoted, active combo. */
        ACTIVE
    }
}
