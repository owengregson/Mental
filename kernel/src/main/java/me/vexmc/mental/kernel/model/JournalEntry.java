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
 * @param paceFactor     the speed-conformal pace factor actually applied to the
 *                       fresh horizontal knock (D-6). {@code 1.0} whenever pace is
 *                       off, the hit was suppressed before compute, or the hit is a
 *                       projectile — so a weak knock is attributable in one journal
 *                       read (a {@code 0.769}-class factor is a stance desync, a
 *                       {@code 2.0} a hard clamp). A plain {@code double}, so it
 *                       crosses the tester boundary without a downgraded stub type
 *                       (the D-8 rule).
 */
public record JournalEntry(HitId id, HitSource source, KnockbackVector shipped,
                           boolean wireCarried, String suppressReason, TickStamp at,
                           double paceFactor) {

    /**
     * Additive growth: the old-arity constructor defaults {@link #paceFactor} to
     * {@code 1.0} (the no-pace record), so every construction that predates D-6
     * builds unchanged.
     */
    public JournalEntry(HitId id, HitSource source, KnockbackVector shipped,
                        boolean wireCarried, String suppressReason, TickStamp at) {
        this(id, source, shipped, wireCarried, suppressReason, at, 1.0);
    }
}
