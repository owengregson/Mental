package me.vexmc.mental.kernel.combo;

import java.util.UUID;

/**
 * What one {@link ComboTracker} mutation did to the combo's lifecycle — the
 * single value each tracker method returns so the core can fire the additive
 * {@code ComboStartEvent}/{@code ComboEndEvent} on the owning region thread
 * without reaching into tracker internals (combo-hold §4). The kernel stays
 * Bukkit-free: it reports the transition as data; the core maps it to the api
 * event and calls it.
 *
 * <p>A single mutation reports at most one transition: a hit that ends an old
 * chain (attacker switch / gap expiry) restarts a fresh chain at one hit, which
 * cannot re-activate in the same call, so START and END never co-occur.</p>
 *
 * @param kind      whether a combo just started, just ended, or nothing changed.
 * @param attacker  the combo's attacker (for a START, the new one; for an END,
 *                  the one whose combo ended); null for {@link Kind#NONE}.
 * @param hits      the chain length at a START; unspecified otherwise.
 * @param reason    why the combo ended (an END); null otherwise.
 */
public record ComboTransition(Kind kind, UUID attacker, int hits, ComboEndReason reason) {

    /** The three lifecycle outcomes of a tracker mutation. */
    public enum Kind { NONE, STARTED, ENDED }

    /** The no-op transition — the common case each tick. */
    public static final ComboTransition NONE = new ComboTransition(Kind.NONE, null, 0, null);

    static ComboTransition started(UUID attacker, int hits) {
        return new ComboTransition(Kind.STARTED, attacker, hits, null);
    }

    static ComboTransition ended(UUID attacker, ComboEndReason reason) {
        return new ComboTransition(Kind.ENDED, attacker, 0, reason);
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
