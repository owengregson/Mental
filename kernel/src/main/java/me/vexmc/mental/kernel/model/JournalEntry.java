package me.vexmc.mental.kernel.model;

/**
 * One terminal record in the delivery journal (R13/B7) — the single "what did
 * we actually ship" seam. Written only by the desk; read by the tester, the
 * debug sink, and nothing else. Immutable.
 *
 * @param shipped        the final delivered vector, or null for a cancelled/suppressed hit.
 * @param wireCarried    true when the wire carried it and a valve was armed;
 *                       false for a correction, a pinned ship, or a suppression.
 * @param suppressReason the reason a hit was suppressed/dropped, or null.
 */
public record JournalEntry(HitId id, HitSource source, KnockbackVector shipped,
                           boolean wireCarried, String suppressReason, TickStamp at) {
}
