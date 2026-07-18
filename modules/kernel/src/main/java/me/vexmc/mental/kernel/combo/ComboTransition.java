package me.vexmc.mental.kernel.combo;

import java.util.UUID;
import me.vexmc.mental.kernel.model.TickStamp;

/**
 * What one {@link ComboTracker} mutation did to the combo's lifecycle — the
 * value each tracker method returns so the core can fire the additive api combo
 * events on the owning region thread without reaching into tracker internals
 * (combo-hold §4, widened for gen-3). The kernel stays Bukkit-free: it reports
 * the transition as data; the core maps it to the api event and calls it.
 *
 * <p>The vocabulary surfaces every edge, not just active start/end: a
 * developing chain now reports {@link Kind#CHAIN_OPENED} on its first hit,
 * {@link Kind#CHAIN_ADVANCED} on each pre-activation hit after it, and
 * {@link Kind#CHAIN_ABORTED} when it dies before activating; an active chain
 * reports {@link Kind#STARTED} at promotion and {@link Kind#HIT} on every
 * continuation. The gen-3 §5.6 ordering guarantee holds within a returned list:
 * a terminal ({@link Kind#ENDED} / {@link Kind#CHAIN_ABORTED}) for the old
 * sequence always precedes the opening transition of its successor.</p>
 *
 * @param kind        which lifecycle edge this mutation crossed.
 * @param attacker    the chain's attacker (for a terminal, the one whose chain
 *                    ended; for an opening/advance, the current one); null for
 *                    {@link Kind#NONE}.
 * @param hits        the chain length at this transition (the final length for a
 *                    terminal); unspecified for {@link Kind#NONE}.
 * @param reason      why an ACTIVE combo ended (an {@link Kind#ENDED}); null otherwise.
 * @param abortReason why a DEVELOPING chain aborted (a {@link Kind#CHAIN_ABORTED});
 *                    null otherwise.
 * @param tick        the tick this transition occurred; {@link TickStamp#NO_TICK}
 *                    for {@link Kind#NONE}.
 * @param gapDeadline the tick by which the next qualifying knock must ship to keep
 *                    the chain alive (an opening/advance/hit/start);
 *                    {@link TickStamp#NO_TICK} for terminals and {@link Kind#NONE}.
 */
public record ComboTransition(Kind kind, UUID attacker, int hits, ComboEndReason reason,
                              ComboAbortReason abortReason, TickStamp tick, TickStamp gapDeadline) {

    /** Every lifecycle edge a tracker mutation can cross. */
    public enum Kind { NONE, CHAIN_OPENED, CHAIN_ADVANCED, CHAIN_ABORTED, STARTED, HIT, ENDED }

    /** The no-op transition — the common case each tick. */
    public static final ComboTransition NONE =
            new ComboTransition(Kind.NONE, null, 0, null, null, TickStamp.NO_TICK, TickStamp.NO_TICK);

    public static ComboTransition chainOpened(UUID attacker, TickStamp tick, TickStamp gapDeadline) {
        return new ComboTransition(Kind.CHAIN_OPENED, attacker, 1, null, null, tick, gapDeadline);
    }

    public static ComboTransition chainAdvanced(UUID attacker, int hits, TickStamp tick, TickStamp gapDeadline) {
        return new ComboTransition(Kind.CHAIN_ADVANCED, attacker, hits, null, null, tick, gapDeadline);
    }

    public static ComboTransition chainAborted(UUID attacker, int hits, ComboAbortReason abortReason, TickStamp tick) {
        return new ComboTransition(Kind.CHAIN_ABORTED, attacker, hits, null, abortReason, tick, TickStamp.NO_TICK);
    }

    public static ComboTransition started(UUID attacker, int hits, TickStamp tick, TickStamp gapDeadline) {
        return new ComboTransition(Kind.STARTED, attacker, hits, null, null, tick, gapDeadline);
    }

    public static ComboTransition hit(UUID attacker, int hits, TickStamp tick, TickStamp gapDeadline) {
        return new ComboTransition(Kind.HIT, attacker, hits, null, null, tick, gapDeadline);
    }

    public static ComboTransition ended(UUID attacker, int hits, ComboEndReason reason, TickStamp tick) {
        return new ComboTransition(Kind.ENDED, attacker, hits, reason, null, tick, TickStamp.NO_TICK);
    }

    /** A combo became active on this mutation. */
    public boolean started() {
        return kind == Kind.STARTED;
    }

    /** An active combo ended on this mutation. */
    public boolean ended() {
        return kind == Kind.ENDED;
    }
}
