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
 * a hit spends it via {@link #onServerClear(TickStamp)}. Server-initiated
 * {@code setSprinting} drift never crosses the wire, so {@link #reconcile}
 * adopts the live flag only after the wire has been quiet — a fresh wire write
 * always wins the within-tick window it exists for. All timing is
 * {@link TickStamp} deltas; no wall clock.</p>
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
 * survives Mental's own post-hit {@code setSprinting(false)} the way the era's
 * client flag did.</p>
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
     * {@code seen} gates reconcile seeding; {@code lastWrite} is the
     * newer-wire-write-wins clock.
     */
    private record State(boolean seen, boolean sprinting, boolean armed,
                         boolean clientSprinting, boolean blockReset, TickStamp lastWrite) {}

    private static final State INITIAL =
            new State(false, false, false, false, false, TickStamp.NO_TICK);

    private final TickClock clock;
    private final AtomicReference<State> state = new AtomicReference<>(INITIAL);

    public SprintWire(TickClock clock) {
        this.clock = clock;
    }

    /** A wire START: sets the flag, arms freshness, and raises the raw client flag (the re-engage is the w-tap). */
    public void onSprintStart() {
        TickStamp now = clock.current();
        state.updateAndGet(s -> new State(true, true, true, true, s.blockReset(), now));
    }

    /** A wire STOP: drops the flag and the raw client flag — armed freshness survives the release half. */
    public void onSprintStop() {
        TickStamp now = clock.current();
        state.updateAndGet(s -> new State(true, false, s.armed(), false, s.blockReset(), now));
    }

    /**
     * Stamp-guarded mirror of vanilla's in-attack sprint clear, applied when the
     * desk reports an accepted bonus hit: the flag drops and the freshness the hit
     * used is spent. The raw {@link #clientSprinting} flag is left ALONE — only
     * START/STOP packets write it.
     *
     * <p>NO-OPS ENTIRELY when the wire holds a write strictly newer than
     * {@code asOf} (the hit's verdict stamp): vanilla cleared sprint synchronously
     * inside {@code attack}, so it could never eat a re-engage that arrived
     * afterwards, but Mental's clear runs 1–2 ticks late at the deferred EDBEE. A
     * later START must survive — the same newer-wire-write-wins rule
     * {@link #reconcile} implements. A later wire write also refreshes
     * {@code lastWrite}, so this clear never counts as a wire write. (A same-tick
     * START-after-ATTACK is an accepted residual — physically implausible input.)</p>
     */
    public void onServerClear(TickStamp asOf) {
        TickStamp now = clock.current();
        state.updateAndGet(s -> {
            if (s.lastWrite().known() && asOf.known() && s.lastWrite().value() > asOf.value()) {
                return s; // a newer wire write wins — never retro-clear a later re-engage
            }
            // blockReset survives: a held sword-block re-arms every hit of the combo,
            // so the freshness this hit spent must NOT drop while the block is held.
            return new State(true, false, false, s.clientSprinting(), s.blockReset(), now);
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
        state.updateAndGet(s -> new State(true, false, false, s.clientSprinting(), s.blockReset(), now));
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
     * dropped the freshness the first hit spent. The reset is ended by
     * {@link #onBlockReleased} (the block button releasing) or by a STOP_SPRINTING
     * lowering the raw client flag.
     */
    public void onBlockSprintReset() {
        TickStamp now = clock.current();
        state.updateAndGet(s -> new State(true, true, true, true, true, now));
    }

    /**
     * The held sword-block was released — the client's RELEASE_USE_ITEM, observed
     * on the connection's own netty thread. Drops the sticky {@code blockReset} so
     * a hit after the release falls straight back to the ordinary wire verdict
     * (the "while currently blocking" boundary of the blockhit contract). It
     * touches nothing else — not {@code lastWrite} (a release is not a sprint wire
     * write) — and is a no-op when no reset is held, so the always-inbound
     * RELEASE_USE_ITEM packets of any other item use (eating, drawing a bow) never
     * churn the wire.
     */
    public void onBlockReleased() {
        state.updateAndGet(s -> s.blockReset()
                ? new State(s.seen(), s.sprinting(), s.armed(), s.clientSprinting(), false, s.lastWrite())
                : s);
    }

    /**
     * Re-seed from the published server flag after the wire has been quiet for
     * {@code quietTicks}, or when the wire has never been written (absent). A
     * fresh wire write inside the window is never overwritten by a stale-high
     * server flag. Never writes {@link #clientSprinting} — that is packet-only.
     */
    public void reconcile(boolean serverSprinting, TickStamp now, int quietTicks) {
        state.updateAndGet(s -> {
            if (!s.seen()) {
                return new State(true, serverSprinting, false, s.clientSprinting(), s.blockReset(), now);
            }
            if (s.sprinting() != serverSprinting && s.lastWrite().known() && now.known()
                    && now.value() - s.lastWrite().value() >= quietTicks) {
                return new State(true, serverSprinting, s.armed(), s.clientSprinting(), s.blockReset(), now);
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
        return new SprintVerdict(sprinting, fresh, now);
    }

    /**
     * The RAW client sprint flag — set only by {@link #onSprintStart}/{@link
     * #onSprintStop}, never by a server clear or reconcile. This is the only
     * signal that survives Mental's own post-hit {@code setSprinting(false)}, so
     * block-hit re-arming ({@code SwordBlockingUnit}) gates on it to avoid a
     * phantom bonus on a stationary defensive block (era-accuracy contract).
     */
    public boolean clientSprinting() {
        return state.get().clientSprinting();
    }
}
