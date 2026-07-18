package me.vexmc.mental.kernel.model;

/**
 * One terminal record in the delivery journal (R13/B7) — the single "what did
 * we actually ship" seam. Written only by the desk; read by the tester, the
 * debug sink, and nothing else. Immutable.
 *
 * @param shipped        the final delivered vector, or null for a cancelled/suppressed hit.
 * @param wireCarried    true when the wire carried it and a valve was armed;
 *                       false for a correction, a pinned ship, or a suppression.
 * @param suppressReason the reason a hit was suppressed/dropped, or null. A SHIPPED
 *                       pinned entry may carry the {@code wire-failed} note — the
 *                       pre-send burst the wire refused; the knock still shipped once
 *                       via the velocity event.
 * @param paceFactor     the speed-conformal pace factor actually applied to the
 *                       fresh horizontal knock (D-6). {@code 1.0} whenever pace is
 *                       off, the hit was suppressed before compute, or the hit is a
 *                       projectile — so a weak knock is attributable in one journal
 *                       read (a {@code 0.769}-class factor is a stance desync, a
 *                       {@code 2.0} a hard clamp). A plain {@code double}, so it
 *                       crosses the tester boundary without a downgraded stub type
 *                       (the D-8 rule).
 * @param comboFactor    the pocket-servo factor actually applied to the fresh
 *                       horizontal knock (combo-hold §3.2, D-6). {@code 1.0}
 *                       whenever the servo was off/not-this-attacker/no-lever, the
 *                       hit was suppressed before compute, or the hit is a
 *                       projectile — so a non-era combo stamp is attributable in
 *                       one journal read (a {@code 0.8}/{@code 1.2} is a hard servo
 *                       clamp). A plain {@code double} (D-8).
 */
public record JournalEntry(HitId id, HitSource source, KnockbackVector shipped,
                           boolean wireCarried, String suppressReason, TickStamp at,
                           double paceFactor, double comboFactor, Capture capture) {

    /**
     * The F9 per-hit delivery capture — the investigation's discriminating
     * measurement as a permanent journal field. Null on entries built through a
     * pre-F9 arity (additive growth) and on sources that never stamp (projectiles).
     *
     * <p>{@code presend} and {@code resolution} are open STRING namespaces (the
     * {@link #suppressReason} precedent), so parallel lanes extend them with new
     * values, never a type change: F2 adds its pre-send outcome pins (e.g.
     * "unsendable-downgrade"); F4 adds its supersede-passthrough resolutions.</p>
     *
     * <p>presend values (stamped at registration): "wire", "pinned", "paced-out",
     * "suppressed:anticheat", "suppressed:resistance-roll",
     * "suppressed:frozen-immune", "no-view", "off"; null = the region path (no
     * fast-path pre-send existed — the formatter prints "none").</p>
     *
     * <p>resolution values (chosen by the desk branch that journaled):
     * "ship-valve", "ship-corrected", "ship-pinned", "ship-formula", "ensured",
     * "cancel", "superseded", "drop", "sweep", "late-resolve".</p>
     *
     * <p>{@code sprintFresh} is {@link Boolean} (Java 8 — D-8 safe), nullable =
     * "no wire view existed", mirroring {@code SprintVerdict.fresh()}.</p>
     */
    public record Capture(boolean sprinting, Boolean sprintFresh, String presend,
                          String resolution, HitGeometry geometry, String profile) {
    }

    /**
     * Additive growth (F9): the pre-F9 canonical arity defaults {@link #capture}
     * to {@code null}, so every construction that predates the delivery capture
     * builds unchanged.
     */
    public JournalEntry(HitId id, HitSource source, KnockbackVector shipped,
                        boolean wireCarried, String suppressReason, TickStamp at,
                        double paceFactor, double comboFactor) {
        this(id, source, shipped, wireCarried, suppressReason, at, paceFactor, comboFactor, null);
    }

    /**
     * Additive growth (combo-hold): the 2.4.1 arity defaults {@link #comboFactor}
     * to {@code 1.0} (the no-servo record), so every construction that predates
     * the pocket servo builds unchanged.
     */
    public JournalEntry(HitId id, HitSource source, KnockbackVector shipped,
                        boolean wireCarried, String suppressReason, TickStamp at,
                        double paceFactor) {
        this(id, source, shipped, wireCarried, suppressReason, at, paceFactor, 1.0);
    }

    /**
     * Additive growth: the original arity defaults {@link #paceFactor} and {@link
     * #comboFactor} to {@code 1.0} (the no-pace, no-servo record), so every
     * construction that predates D-6 builds unchanged.
     */
    public JournalEntry(HitId id, HitSource source, KnockbackVector shipped,
                        boolean wireCarried, String suppressReason, TickStamp at) {
        this(id, source, shipped, wireCarried, suppressReason, at, 1.0, 1.0);
    }
}
