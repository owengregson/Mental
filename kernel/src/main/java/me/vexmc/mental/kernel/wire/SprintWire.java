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
 */
public final class SprintWire {

    /**
     * The whole wire state as one immutable value. {@code clientSprinting} is the
     * raw client-packet flag; {@code sprinting}/{@code armed} are the era wire
     * view a hit consumes; {@code seen} gates reconcile seeding; {@code lastWrite}
     * is the newer-wire-write-wins clock.
     */
    private record State(boolean seen, boolean sprinting, boolean armed,
                         boolean clientSprinting, TickStamp lastWrite) {}

    private static final State INITIAL = new State(false, false, false, false, TickStamp.NO_TICK);

    private final TickClock clock;
    private final AtomicReference<State> state = new AtomicReference<>(INITIAL);

    public SprintWire(TickClock clock) {
        this.clock = clock;
    }

    /** A wire START: sets the flag, arms freshness, and raises the raw client flag (the re-engage is the w-tap). */
    public void onSprintStart() {
        TickStamp now = clock.current();
        state.updateAndGet(s -> new State(true, true, true, true, now));
    }

    /** A wire STOP: drops the flag and the raw client flag — armed freshness survives the release half. */
    public void onSprintStop() {
        TickStamp now = clock.current();
        state.updateAndGet(s -> new State(true, false, s.armed(), false, now));
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
            return new State(true, false, false, s.clientSprinting(), now);
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
        state.updateAndGet(s -> new State(true, false, false, s.clientSprinting(), now));
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
                return new State(true, serverSprinting, false, s.clientSprinting(), now);
            }
            if (s.sprinting() != serverSprinting && s.lastWrite().known() && now.known()
                    && now.value() - s.lastWrite().value() >= quietTicks) {
                return new State(true, serverSprinting, s.armed(), s.clientSprinting(), now);
            }
            return s;
        });
    }

    /** The registration-time verdict (never null; falls back to the seeded state). */
    public SprintVerdict verdictAt(TickStamp now) {
        State s = state.get();
        return new SprintVerdict(s.sprinting(), s.armed(), now);
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
