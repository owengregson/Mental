package me.vexmc.mental.kernel.wire;

import java.util.concurrent.atomic.AtomicReference;
import me.vexmc.mental.kernel.model.SprintVerdict;
import me.vexmc.mental.kernel.model.TickStamp;
import me.vexmc.mental.kernel.port.TickClock;

/**
 * Arrival-order sprint truth for ONE attacker (spec §2, D1; §4). The era server
 * applied inbound packets in arrival order, so an attack always read the sprint
 * flag with every earlier START/STOP already applied — a w-tap registered no
 * matter how little time separated the re-press from the click. This replays
 * that in-order read with no tick gating: {@link #verdictAt} returns the current
 * wire state directly.
 *
 * <p>Freshness (WindSpigot's {@code isExtraKnockback}): a START arms it (the
 * re-engage IS the w-tap signal); the release half of a tap never disarms it;
 * a hit spends it via {@link #onServerClear(long)}. Server-initiated
 * {@code setSprinting} drift never crosses the wire, so {@link #reconcile}
 * adopts the live flag only after the wire has been quiet — a fresh wire write
 * always wins the within-tick window it exists for. The newer-wire-write-wins
 * rule that protects a re-engage from the deferred post-hit clear is sequenced
 * by a per-wire monotonic {@code seq} (arrival order, bumped by every wire
 * write) for wire-peeked verdicts, so a same-tick START-after-ATTACK cannot
 * collide with the hit the way it did at tick granularity; {@code lastWrite}
 * remains the reconcile quiet clock. All timing is {@link TickStamp} deltas or
 * the monotonic {@code seq}; no wall clock.</p>
 *
 * <p><b>The post-clear re-arm</b> (the modern-client sprint latch fix,
 * 2026-07-10). Mental NO LONGER defers a {@code player.setSprinting(false)} after
 * a bonus hit: on 1.21.2+ that server-flag write is ECHOED to the attacker's own
 * client, which adopts it, drops its local sprint, and confirms with one
 * STOP_SPRINTING — latching {@link #clientSprinting} false with no START ever
 * returning (item-use block-hitting blocks a fresh sprint start). Only the WIRE
 * clear ({@link #onServerClear}) remains. To reproduce the era's observable wire
 * cadence (bonus → one-tick gap → re-arm on held intent) without that echo, the
 * clear records {@code clearedAt} and {@link #reconcile} re-arms {@code sprinting}
 * on the next movement packet ≥1 tick later WHEN the raw {@code clientSprinting}
 * flag survived (i.e. no STOP followed the hit — the client never expressed
 * un-sprint). A client that DID predict the attack un-sprint (legacy via Via, a
 * CADENCE-spoofed modern client, a slow full-charge clicker) sends STOP →
 * {@code clientSprinting} false → no auto re-arm → a real START (the w-tap) is
 * still required, freshness armed as ever: the client-side technique contract is
 * preserved exactly where a client expresses it. {@code clearedAt} is reset to
 * {@code NO_TICK} on any client START/STOP and on a reconcile ADOPT, so a genuine
 * un-sprint is never overridden.</p>
 *
 * <p><b>State is one immutable snapshot swapped by reference</b> (the codebase
 * idiom). The primary owner is the connection's netty thread (START/STOP,
 * reconcile, verdict reads), but the desk's post-hit clear runs on the victim's
 * region thread and {@code SwordBlockingUnit} reads {@link #clientSprinting} on
 * the attacker's — so the fields are read/written across threads. Every mutation
 * is a CAS over the whole {@link State}, giving each read a coherent, atomic view
 * (no torn mix), and each write happens-before the next read. The
 * {@link #clientSprinting} flag tracks the RAW client sprint packets ONLY — it is
 * never touched by {@link #onServerClear(TickStamp)} or {@link #reconcile}, so it
 * survives the wire's own post-hit clear the way the era's client flag did.</p>
 *
 * <p><b>The universal blockhit contract</b> (era-accuracy; the owner's directive):
 * a sword block engages by re-arming sprint (the era technique's whole purpose),
 * and while that SAME engagement is held every hit ships the FRESH sprint knock —
 * even the second and later hits of a held-block combo, whose wire
 * {@code sprinting}/{@code armed} the prior hit's {@link #onServerClear} already
 * dropped. This is the sticky {@code blockReset}: raised by
 * {@link #onBlockSprintReset} on the block right-click, gated by the raw client
 * flag in {@link #verdictAt} (a STOP_SPRINTING ends it — you are no longer
 * moving), and dropped by {@link #onBlockReleased} when the block button is
 * released (the "while currently blocking" boundary). It survives
 * {@link #onServerClear} — that is the whole point. Stamp-ordered, no wall clock;
 * a hit past the release or past a STOP falls straight back to the ordinary wire
 * verdict.</p>
 */
public final class SprintWire {

    /**
     * The whole wire state as one immutable value. {@code clientSprinting} is the
     * raw client-packet flag; {@code sprinting}/{@code armed} are the era wire
     * view a hit consumes; {@code blockReset} is the held-sword-block sprint reset
     * (the universal blockhit contract — sticky across {@link #onServerClear});
     * {@code clearedAt} is the tick a post-hit {@link #onServerClear} last fired —
     * the re-arm window's open (its {@code NO_TICK} sentinel means "no hit clear is
     * awaiting a re-engage"; reset by any client START/STOP and by a reconcile
     * adopt so a genuine un-sprint is never re-armed over); {@code seen} gates
     * reconcile seeding; {@code lastWrite} is the reconcile quiet clock; {@code seq}
     * counts WIRE WRITES in arrival order — client START/STOP, the block-hit re-arm,
     * the post-clear re-arm, and a reconcile seed/adopt each bump it, so it IS this
     * wire's era queue program order. {@link #onBlockReleased} and
     * {@link #onServerClear} do NOT bump: a release only drops the sticky
     * {@code blockReset} (deliberately not a sprint write, matching its
     * {@code lastWrite} exemption), and the clear is the CONSUMER of the ordering,
     * never a producer.
     */
    private record State(boolean seen, boolean sprinting, boolean armed,
                         boolean clientSprinting, boolean blockReset, TickStamp clearedAt,
                         TickStamp lastWrite, long seq) {}

    private static final State INITIAL =
            new State(false, false, false, false, false, TickStamp.NO_TICK, TickStamp.NO_TICK, 0L);

    private final TickClock clock;
    private final AtomicReference<State> state = new AtomicReference<>(INITIAL);

    public SprintWire(TickClock clock) {
        this.clock = clock;
    }

    /**
     * A wire START: sets the flag, arms freshness, and raises the raw client flag
     * (the re-engage is the w-tap). Resets {@code clearedAt} — a real START is a
     * genuine sprint intent, not the automatic post-clear re-arm.
     */
    public void onSprintStart() {
        TickStamp now = clock.current();
        state.updateAndGet(s -> new State(true, true, true, true, s.blockReset(), TickStamp.NO_TICK, now, s.seq() + 1));
    }

    /**
     * A wire STOP: drops the flag and the raw client flag — armed freshness survives
     * the release half. Resets {@code clearedAt} — the client expressed un-sprint, so
     * the post-clear re-arm must never override it (the w-tap contract).
     */
    public void onSprintStop() {
        TickStamp now = clock.current();
        state.updateAndGet(s -> new State(true, false, s.armed(), false, s.blockReset(), TickStamp.NO_TICK, now, s.seq() + 1));
    }

    /**
     * Stamp-guarded mirror of vanilla's in-attack sprint clear, applied when the
     * desk reports an accepted bonus hit whose verdict carries NO wire provenance
     * ({@code wtapConsultWire=false} fallback and non-melee mints — a wire-peeked
     * verdict uses {@link #onServerClear(long)}, the authoritative arrival-order
     * guard). The flag drops and the freshness the hit used is spent, and
     * {@code clearedAt} is recorded so the next movement reconcile can re-arm the
     * bonus a tick later (the era one-tick re-engage) IF the raw client flag
     * survived. The raw {@link #clientSprinting} flag is left ALONE — only
     * START/STOP packets write it.
     *
     * <p>NO-OPS ENTIRELY when the wire holds a write strictly newer than
     * {@code asOf} (the hit's verdict stamp): vanilla cleared sprint synchronously
     * inside {@code attack}, so it could never eat a re-engage that arrived
     * afterwards, but Mental's clear runs 1–2 ticks late at the deferred EDBEE. A
     * later START must survive — the same newer-wire-write-wins rule
     * {@link #reconcile} implements. A later wire write also refreshes
     * {@code lastWrite}, so this clear never counts as a wire write.</p>
     */
    public void onServerClear(TickStamp asOf) {
        TickStamp now = clock.current();
        state.updateAndGet(s -> {
            if (s.lastWrite().known() && asOf.known() && s.lastWrite().value() > asOf.value()) {
                return s; // a newer wire write wins — never retro-clear a later re-engage
            }
            // blockReset survives: a held sword-block re-arms every hit of the combo,
            // so the freshness this hit spent must NOT drop while the block is held.
            // clearedAt = now opens the post-clear re-arm window (reconcile re-engages
            // ≥1 tick later while the raw client flag survives).
            return new State(true, false, false, s.clientSprinting(), s.blockReset(), now, now, s.seq());
        });
    }

    /**
     * Sequence-guarded mirror of vanilla's in-attack sprint clear. {@code asOfSeq}
     * is the wire sequence the hit's verdict peeked ({@link SprintVerdict#wireSeq()});
     * the clear applies only when NO wire write has arrived since that peek —
     * arrival order, not tick granularity, so a w-tap START landing in the SAME
     * tick as (and after) the ATTACK survives the clear that belongs to that
     * ATTACK (the 2.4.x same-tick retro-clear defect; vanilla's synchronous
     * in-attack clear could never eat a later-arriving START). blockReset and the
     * raw clientSprinting flag survive as ever, and {@code clearedAt} is recorded so
     * the post-clear re-arm can re-engage a tick later; an applied clear still
     * refreshes {@code lastWrite} so the reconcile's quiet window cannot re-adopt the
     * not-yet-cleared server flag, but it never bumps {@code seq} — the clear is
     * a consumer of the arrival order, never a producer.
     */
    public void onServerClear(long asOfSeq) {
        TickStamp now = clock.current();
        state.updateAndGet(s -> {
            if (s.seq() > asOfSeq) {
                return s; // a wire write arrived after the verdict peek — never retro-clear it
            }
            return new State(true, false, false, s.clientSprinting(), s.blockReset(), now, now, s.seq());
        });
    }

    /**
     * The unconditional, retro-clearing form.
     *
     * @deprecated Superseded by {@link #onServerClear(TickStamp)} (2.4.1). This
     *     form clears sprint/freshness with NO guard against a newer wire write,
     *     so a re-engage START arriving in the deferred-EDBEE window {@code [T,
     *     T+2]} was retroactively destroyed and the next hit shipped with no
     *     sprint extra (F2). Retained for the pre-guard behaviour only.
     */
    @Deprecated
    public void onServerClear() {
        TickStamp now = clock.current();
        state.updateAndGet(s -> new State(true, false, false, s.clientSprinting(), s.blockReset(), now, now, s.seq()));
    }

    /**
     * A sword-block engagement re-armed sprint and is now HELD — the universal
     * blockhit contract (era-accuracy). Called by {@code SwordBlockingUnit} on the
     * block right-click, gated there on the raw client sprint flag, a sanctioned
     * cross-thread writer (the attacker's own thread, atomic under the CAS). It
     * re-arms sprint exactly like {@link #onSprintStart} AND raises the sticky
     * {@code blockReset}: while the SAME engagement is held, {@link #verdictAt}
     * reports a FRESH sprint verdict on every hit, so a held-block combo's second
     * and later hits stay fresh where {@link #onServerClear} would otherwise have
     * dropped the freshness the first hit spent. It resets {@code clearedAt} (a
     * genuine re-engage, like a START). The reset is ended by
     * {@link #onBlockReleased} (the block button releasing) or by a STOP_SPRINTING
     * lowering the raw client flag.
     */
    public void onBlockSprintReset() {
        TickStamp now = clock.current();
        state.updateAndGet(s -> new State(true, true, true, true, true, TickStamp.NO_TICK, now, s.seq() + 1));
    }

    /**
     * The held sword-block was released — the client's RELEASE_USE_ITEM, observed
     * on the connection's own netty thread. Drops the sticky {@code blockReset} so
     * a hit after the release falls straight back to the ordinary wire verdict
     * (the "while currently blocking" boundary of the blockhit contract). It
     * touches nothing else — not {@code lastWrite} (a release is not a sprint wire
     * write), not {@code clearedAt} — and is a no-op when no reset is held, so the
     * always-inbound RELEASE_USE_ITEM packets of any other item use (eating,
     * drawing a bow) never churn the wire.
     */
    public void onBlockReleased() {
        state.updateAndGet(s -> s.blockReset()
                ? new State(s.seen(), s.sprinting(), s.armed(), s.clientSprinting(), false,
                        s.clearedAt(), s.lastWrite(), s.seq())
                : s);
    }

    /**
     * Re-seed from the published server flag after the wire has been quiet for
     * {@code quietTicks}, or when the wire has never been written (absent); AND
     * re-arm the bonus after a post-hit clear when the raw client flag survived
     * (the modern-client sprint latch fix). A fresh wire write inside the window is
     * never overwritten by a stale-high server flag. Never writes
     * {@link #clientSprinting} — that is packet-only.
     *
     * <p>Three branches, in priority order:</p>
     * <ol>
     *   <li><b>Post-clear re-arm</b>: a movement reconcile ≥1 tick after an
     *       {@link #onServerClear} ({@code clearedAt} known, {@code now} strictly
     *       after it) with {@code !sprinting && clientSprinting} — the client never
     *       sent a STOP, so its held sprint intent survived the hit. Re-engage
     *       {@code sprinting} at the era one-tick re-engage cadence; {@code armed}
     *       is UNTOUCHED (freshness comes only from a real START). {@code clearedAt}
     *       is preserved so a re-fire is idempotent (the next {@code sprinting}
     *       state fails the {@code !sprinting} guard) until the next hit's clear
     *       reopens the window.</li>
     *   <li><b>Seed</b>: an unseen wire adopts the live flag immediately (not
     *       fresh — no re-engage happened).</li>
     *   <li><b>Adopt</b>: after {@code quietTicks} of wire silence the server's own
     *       flag wins a disagreement. {@code clearedAt} is reset — the server has
     *       authoritatively asserted its flag, closing any post-hit re-arm window
     *       (a genuine server un-sprint is never re-armed over).</li>
     * </ol>
     */
    public void reconcile(boolean serverSprinting, TickStamp now, int quietTicks) {
        state.updateAndGet(s -> {
            if (!s.sprinting() && s.clientSprinting()
                    && s.clearedAt().known() && now.known()
                    && now.value() > s.clearedAt().value()) {
                // Post-clear re-arm: the client held sprint through the hit (no STOP),
                // so re-engage the bonus the wire's own clear dropped — the era wire
                // cadence the removed setSprinting(false) echo used to fake by proxy.
                return new State(s.seen(), true, s.armed(), s.clientSprinting(), s.blockReset(),
                        s.clearedAt(), now, s.seq() + 1);
            }
            if (!s.seen()) {
                return new State(true, serverSprinting, false, s.clientSprinting(), s.blockReset(),
                        s.clearedAt(), now, s.seq() + 1);
            }
            if (s.sprinting() != serverSprinting && s.lastWrite().known() && now.known()
                    && now.value() - s.lastWrite().value() >= quietTicks) {
                return new State(true, serverSprinting, s.armed(), s.clientSprinting(), s.blockReset(),
                        TickStamp.NO_TICK, now, s.seq() + 1);
            }
            return s;
        });
    }

    /**
     * The registration-time verdict (never null; falls back to the seeded state).
     *
     * <p>A held sword-block sprint reset ({@code blockReset}) overrides the wire
     * view while the raw client flag survives: a hit delivered while the block is
     * still held reports FRESH sprint regardless of a prior hit's
     * {@link #onServerClear} having dropped {@code sprinting}/{@code armed} — the
     * universal blockhit contract. A STOP_SPRINTING drops {@code clientSprinting}
     * and so ends the override (the attacker is no longer moving); the block
     * release drops {@code blockReset} itself. Otherwise the verdict is the plain
     * arrival-order wire view, byte-identical to before.</p>
     */
    public SprintVerdict verdictAt(TickStamp now) {
        State s = state.get();
        boolean blockHeldReset = s.blockReset() && s.clientSprinting();
        boolean sprinting = blockHeldReset || s.sprinting();
        boolean fresh = blockHeldReset || s.armed();
        return new SprintVerdict(sprinting, fresh, now, s.seq());
    }

    /**
     * The RAW client sprint flag — set only by {@link #onSprintStart}/{@link
     * #onSprintStop}, never by a server clear or reconcile. This is the only
     * signal that survives the wire's own post-hit clear, so block-hit re-arming
     * ({@code SwordBlockingUnit}) gates on it to avoid a phantom bonus on a
     * stationary defensive block (era-accuracy contract), and the post-clear
     * re-arm reads it to tell a client that held sprint from one that un-sprinted.
     */
    public boolean clientSprinting() {
        return state.get().clientSprinting();
    }
}
