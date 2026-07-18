package me.vexmc.mental.kernel.delivery;

import me.vexmc.mental.kernel.model.HitContext;
import me.vexmc.mental.kernel.model.JournalEntry;

/**
 * Read-only tap on the desk's journal appends (F9): the desk stays the SOLE
 * journal writer; the observer merely sees each entry the instant it is
 * appended, with the hit's compute-once context for attacker/victim identity.
 * Invoked on the desk's owning thread (every journaling method is owner-called).
 * The desk guards the call — a throwing observer can never break delivery.
 */
@FunctionalInterface
public interface JournalObserver {

    /** The no-op default every pre-F9 construction gets. */
    JournalObserver NONE = (context, entry) -> { };

    void journaled(HitContext context, JournalEntry entry);
}
