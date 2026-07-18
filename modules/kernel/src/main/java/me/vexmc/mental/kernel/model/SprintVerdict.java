package me.vexmc.mental.kernel.model;

/**
 * The attack-time sprint answer, stamped at registration (from the connection
 * domain's {@code InputLedger}) and consumed by the authoritative pass.
 * {@code fresh} is the wire-ordered w-tap freshness, or {@code null} when no
 * wire view existed at registration (the pass then falls back to its own
 * ledger); {@code at} records the tick the verdict was read; {@code wireSeq} is
 * the wire's arrival sequence at peek time — the clear-ordering currency the
 * post-hit sprint clear no-ops against, or {@link #NO_WIRE_SEQ} when the verdict
 * came from the published view rather than a live {@code InputLedger}.
 */
public record SprintVerdict(boolean sprinting, Boolean fresh, TickStamp at, long wireSeq) {

    /** Sentinel: the verdict was not peeked from a InputLedger (published-view fallback, non-melee mints). */
    public static final long NO_WIRE_SEQ = -1L;

    /** Compatibility shape for verdicts with no wire provenance — byte-identical to the pre-seq record. */
    public SprintVerdict(boolean sprinting, Boolean fresh, TickStamp at) {
        this(sprinting, fresh, at, NO_WIRE_SEQ);
    }

    /** Whether this verdict was peeked from a live InputLedger (its {@link #wireSeq} orders the post-hit clear). */
    public boolean fromWire() {
        return wireSeq != NO_WIRE_SEQ;
    }
}
