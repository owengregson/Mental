package me.vexmc.mental.kernel.wire;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import me.vexmc.mental.kernel.model.SprintVerdict;
import me.vexmc.mental.kernel.model.TickStamp;
import me.vexmc.mental.kernel.port.TickClock;

/**
 * The per-attacker input ledger (2.6.0, spec
 * {@code docs/superpowers/specs/2026-07-11-input-ledger-design.md}): the
 * arrival-order sprint truth that was {@code SprintWire}, grown into the one
 * observable authority on sprint resets. The era server applied inbound packets
 * in arrival order, so an attack always read the sprint flag with every earlier
 * START/STOP already applied — a w-tap registered no matter how little time
 * separated the re-press from the click. {@link #verdictAt} replays that
 * in-order read with no tick gating, and every write the machine makes is now
 * also RECORDED into a bounded diagnostic ring ({@link #trail}) so a verdict
 * can always explain itself (the journal's {@code trail=} token) — the
 * fast-wtap contract stops being an article of faith.
 *
 * <p><b>Arrival order is era truth, both ways.</b> A same-tick w-tap+click
 * crosses ATTACK before START on every client generation — 1.8.9
 * ({@code Minecraft.runTick} processes clicks before {@code updateEntities}'
 * sprint diff; bytecode-pinned) and 1.21.11 ({@code handleKeybinds} before the
 * tick's input diff) — so that hit reading plain is correct, never reordered;
 * the trailing START arms the NEXT hit. The journal's {@code note=start-trailed}
 * names the case when it happens live.</p>
 *
 * <p><b>Freshness</b> (WindSpigot's {@code isExtraKnockback}): a START arms it
 * (the re-engage IS the w-tap signal); the release half of a tap never disarms
 * it; a hit spends it via {@link #onServerClear(long)}. Server-initiated
 * {@code setSprinting} drift never crosses the wire, so {@link #reconcile}
 * adopts the live flag only after the wire has been quiet — a fresh wire write
 * always wins the within-tick window it exists for. The newer-wire-write-wins
 * rule that protects a re-engage from the deferred post-hit clear is sequenced
 * by the monotonic {@code seq} (bumped by every RESET GESTURE — client
 * START/STOP, the block re-arm, a reconcile seed/adopt — and by nothing else):
 * it is this wire's era queue program order. {@link #onKeyIntent},
 * {@link #onWindowClose}, {@link #onAttack} and {@link #onBlockReleased} are
 * observations — they ring but bump neither {@code seq} nor {@code lastWrite}
 * (when key intent DID bump seq, 24d9990, any PLAYER_INPUT edge landing in the
 * 1–2-tick deferred-clear window vetoed the consume; see {@link #onKeyIntent}).
 * All timing is {@link TickStamp} deltas or {@code seq}; no wall clock.</p>
 *
 * <p><b>One engagement, one sprint knock</b> (the 2.5.1 arming contract; the
 * owner's directive). Mental does not defer a {@code player.setSprinting(false)}
 * after a bonus hit: on 1.21.2+ that server-flag write is ECHOED to the
 * attacker's own client, which adopts it, drops its local sprint, and confirms
 * with one STOP_SPRINTING — latching {@link #clientSprinting} false with no
 * START ever returning (the modern-client sprint latch; never re-add it). Only
 * the WIRE clear ({@link #onServerClear}) remains, and it CONSUMES the
 * engagement: {@code sprinting} and {@code armed} drop, and {@code clearedAt}
 * opens the SPEND LATCH. Nothing re-arms automatically — re-arming takes a
 * client-expressed re-gesture: a wire STOP→START cycle (w-tap, s-tap, a GUI
 * open), the block-hit re-arm ({@link #onBlockSprintReset}), a seed of an
 * unseen wire, or a server-granted adopt while no consume is outstanding. This
 * is the measured era server contract — real 1.8.9 consumed the flag inside
 * every bonus attack and re-armed only on a client START: a no-w-tap double
 * flew 7.2 blocks where a w-tap double flew 11.4 (era-wire measurements).
 * While the latch is open {@link #reconcile} must not re-adopt the stale-high
 * server flag; the latch closes on any client START/STOP, the block re-arm, an
 * applied adopt, and — new in 2.6.0 — the published server flag's FALLING edge
 * ({@code SERVER_FALL}): a client STOP provably reached vanilla even if this
 * tap missed it (a proxy/translation environment), so the latch degrades to
 * ordinary adoption instead of bricking the session. The failsafe only ever
 * CLOSES the latch; it never arms.</p>
 *
 * <p><b>The block-hit reset is a GESTURE, one hit per reset</b> (2.6.0, the
 * owner's directive — supersedes the 2.5.1 sticky held-chain). A block
 * engagement re-arms sprint EXACTLY like a wire START:
 * {@link #onBlockSprintReset} arms {@code sprinting}+{@code armed}, closes the
 * latch, bumps {@code seq}, and the ordinary engagement spend does the rest —
 * the next accepted bonus hit consumes it, and the NEXT block-hit takes a fresh
 * right-click. No override flag survives the consume (the 2.5.1 {@code
 * blockReset} stickiness was the chain the owner retired), so a held-block
 * combo's second hit ships PLAIN until the block is re-engaged. An unspent
 * block re-arm survives the block RELEASE — era-exact: the era client's
 * re-engage on release is what re-armed the bonus, so a block-tap earns the
 * same one fresh hit a w-tap does. A STOP_SPRINTING still drops the wire view
 * (you are no longer moving — no phantom bonus), and the door's ENTRY gate
 * ({@link #blockReArmEligible}) still rides the raw client flag with the
 * key-intent corroborator. Deferred-consume sliver, documented and pinned: a
 * second hit REGISTERING inside the 1–2-tick window before the first hit's
 * consume lands still reads the armed engagement — the same inherent sliver
 * the seq guard carries; era vanilla's synchronous in-attack clear had no such
 * window, Mental's deferred EDBEE does.</p>
 *
 * <p><b>State is one immutable snapshot swapped by reference</b> (the codebase
 * idiom). The primary owner is the connection's netty thread (START/STOP,
 * key input, reconcile, verdict reads), but the desk's post-hit clear runs on
 * the victim's region thread and the block door reads/writes on the attacker's
 * — every mutation is a CAS over the whole {@link State}, giving each read a
 * coherent atomic view. Ring appends happen strictly AFTER a CAS wins (a
 * retried transition must not ring twice), under the ring's own tiny monitor —
 * uncontended in practice. {@link #clientSprinting} tracks the RAW client
 * sprint packets ONLY — never touched by a clear, written by {@link #reconcile}
 * in exactly one place (the {@code seen==false} SEED, which adopts the server
 * flag as the client's last transmitted sprint state) — so it survives the
 * wire's own post-hit clear the way the era's client flag did.</p>
 */
public final class InputLedger {

    /**
     * The recency window for the PLAYER_INPUT sprint-key corroborator: a held
     * sprint KEY ({@code keyIntent} TRUE) only widens the block-hit entry gate
     * when the wire recorded sprinting within this many ticks. 20 ticks ≈ 1s —
     * long enough to carry an era block-hitter through the one blocked-tick STOP
     * a key-holder ever crosses and its subsequent re-arm cycles, short enough to
     * exclude a stationary defensive ctrl-holder (no sprint in the last second ⇒
     * defending, not block-hitting mid-combo — the phantom-bonus guard). A
     * 1-second "still in the fight" heuristic, not era-tuned wire timing.
     */
    public static final int ERA_BLOCKHIT_RECENCY_TICKS = 20;

    /** The diagnostic ring's capacity — several seconds of gesture-rate events. */
    static final int RING_CAPACITY = 128;

    /**
     * The whole derived state as one immutable value. {@code clientSprinting} is
     * the raw client-packet flag; {@code sprinting}/{@code armed} are the era
     * wire view a hit consumes; {@code clearedAt} is the SPEND LATCH
     * ({@code NO_TICK} = no consume outstanding); {@code seen} gates reconcile
     * seeding; {@code lastWrite} is the reconcile quiet clock; {@code seq}
     * counts RESET GESTURES in arrival order (START/STOP, the block re-arm, a
     * reconcile seed/adopt — the deferred consume's guard currency; observations
     * never bump it); {@code keyIntent} is the raw PLAYER_INPUT sprint-KEY
     * intent (1.21.2+, 0x40; {@code null} = UNKNOWN — pre-1.21.2 / Via /
     * packetless), the block-hit entry-gate corroborator and nothing else;
     * {@code lastSprintingAt} is the tick the wire was last left sprinting (the
     * corroborator's recency); {@code lastServerSprinting} is the previous
     * reconcile's published flag (the SERVER_FALL edge detector; {@code null}
     * until the first reconcile); {@code startsSeen} counts wire STARTs ever
     * (the starvation detector's denominator); {@code starvedConsume} notes a
     * consume that applied before ANY START was ever seen (cleared by the first
     * real START — the feed proved alive); {@code adoptBlockedNoted} dedupes the
     * ADOPT_BLOCKED ring entry to once per latch episode (reconcile runs per
     * movement packet — a per-packet ring flood would drown the trail).
     */
    private record State(boolean seen, boolean sprinting, boolean armed,
                         boolean clientSprinting, TickStamp clearedAt,
                         TickStamp lastWrite, long seq, Boolean keyIntent,
                         TickStamp lastSprintingAt, Boolean lastServerSprinting,
                         int startsSeen, boolean starvedConsume, boolean adoptBlockedNoted) {}

    private static final State INITIAL =
            new State(false, false, false, false, TickStamp.NO_TICK, TickStamp.NO_TICK,
                    0L, null, TickStamp.NO_TICK, null, 0, false, false);

    private final TickClock clock;
    private final AtomicReference<State> state = new AtomicReference<>(INITIAL);

    /** The diagnostic ring — guarded by its own monitor; appends only after a CAS wins. */
    private final ArrayDeque<InputEvent> ring = new ArrayDeque<>();
    private long eventSeq;

    public InputLedger(TickClock clock) {
        this.clock = clock;
    }

    /**
     * A wire START: sets the flag, arms freshness, and raises the raw client flag
     * (the re-engage is the w-tap). Resets {@code clearedAt} — a real START is
     * the genuine re-gesture that closes the spend latch and arms a new
     * engagement — and proves the feed alive (the starvation note clears).
     */
    public void onSprintStart() {
        TickStamp now = clock.current();
        state.updateAndGet(s -> new State(true, true, true, true,
                TickStamp.NO_TICK, now, s.seq() + 1, s.keyIntent(), now,
                s.lastServerSprinting(), s.startsSeen() + 1, false, s.adoptBlockedNoted()));
        record(InputEvent.Kind.SPRINT_START, 0);
    }

    /**
     * A wire STOP: drops the flag and the raw client flag — armed freshness
     * survives the release half. Resets {@code clearedAt} — the client expressed
     * un-sprint (the release half of a w-tap closes the spend latch; the re-press
     * arms the new engagement).
     */
    public void onSprintStop() {
        TickStamp now = clock.current();
        state.updateAndGet(s -> new State(true, false, s.armed(), false,
                TickStamp.NO_TICK, now, s.seq() + 1, s.keyIntent(), s.lastSprintingAt(),
                s.lastServerSprinting(), s.startsSeen(), s.starvedConsume(), s.adoptBlockedNoted()));
        record(InputEvent.Kind.SPRINT_STOP, 0);
    }

    /**
     * Records the client's raw SPRINT KEY intent from a PLAYER_INPUT packet
     * (1.21.2+, the 0x40 flag = {@code keySprint.isDown()}): TRUE for a
     * ctrl/toggle holder even while standing or item-use-blocked, FALSE for a
     * double-tap-W sprinter even while sprinting. It is NEVER a verdict source —
     * {@link #verdictAt} still derives {@code sprinting} only from the wire —
     * only a recency corroborator for the block-hit entry gate
     * ({@link #blockReArmEligible}); its ABSENCE (INITIAL {@code null} — every
     * pre-1.21.2 / Via / packetless client) leaves the gate byte-identical, so
     * defaults stay zero-touch.
     *
     * <p>NOT a gesture: it bumps neither {@code seq} nor {@code lastWrite}. The
     * seq guard exists so a deferred {@link #onServerClear(long)} cannot retro-eat
     * a later GESTURE (a re-engage START) — but a key sample is an observation the
     * clear carries through unchanged, so there is nothing for it to retro-eat.
     * When this DID bump seq (24d9990, reverted in the 2.5.1 verification round),
     * any PLAYER_INPUT edge — the packet fires on ANY of the 7 input bits
     * flipping: a jump, a strafe reverse, a sneak — landing in the 1–2-tick
     * deferred-clear window vetoed the consume, so a bunny-hopping W-holder's
     * engagement was never spent and the per-hit sprint knock survived the
     * spend-latch fix. The full raw byte rides the ring ({@code KEY_INPUT}) for
     * the trail — a double-tap client's W-edge gestures are wire-visible there
     * even though they are deliberately never verdict inputs (a single re-press
     * without a START is genuinely not sprinting; era servers shipped those hits
     * plain). Called on the connection's netty thread; the CAS keeps it atomic
     * against the cross-thread readers.</p>
     */
    public void onKeyIntent(boolean intent, int rawBits) {
        state.updateAndGet(s -> new State(s.seen(), s.sprinting(), s.armed(), s.clientSprinting(),
                s.clearedAt(), s.lastWrite(), s.seq(), intent, s.lastSprintingAt(),
                s.lastServerSprinting(), s.startsSeen(), s.starvedConsume(), s.adoptBlockedNoted()));
        record(InputEvent.Kind.KEY_INPUT, rawBits);
    }

    /** The pre-ring compatibility shape (no raw byte in hand — synthesize the sprint bit). */
    public void onKeyIntent(boolean intent) {
        onKeyIntent(intent, intent ? 0x40 : 0);
    }

    /** A window close crossed the wire — GUI-cycle evidence for the trail, nothing else. */
    public void onWindowClose() {
        record(InputEvent.Kind.WINDOW_CLOSE, 0);
    }

    /** An ATTACK registered against this ledger's verdict — trail context, mutates nothing. */
    public void onAttack() {
        record(InputEvent.Kind.ATTACK, 0);
    }

    /**
     * Sequence-guarded mirror of vanilla's in-attack sprint clear. {@code asOfSeq}
     * is the gesture sequence the hit's verdict peeked
     * ({@link SprintVerdict#wireSeq()}); the clear applies only when NO gesture
     * has arrived since that peek — arrival order, not tick granularity, so a
     * w-tap START landing in the SAME tick as (and after) the ATTACK survives the
     * clear that belongs to that ATTACK (the 2.4.x same-tick retro-clear defect;
     * vanilla's synchronous in-attack clear could never eat a later-arriving
     * START). The raw {@code clientSprinting} flag survives as ever;
     * {@code clearedAt} opens the spend latch; an applied clear refreshes
     * {@code lastWrite} so the reconcile's quiet window cannot re-adopt the
     * not-yet-cleared server flag, but it never bumps {@code seq} — the clear is
     * a consumer of the ordering, never a producer. An APPLIED clear rings
     * {@code CONSUME} (a no-op rings nothing) and notes starvation when it lands
     * on a ledger that has never seen a START.
     */
    public void onServerClear(long asOfSeq) {
        TickStamp now = clock.current();
        for (;;) {
            State s = state.get();
            if (s.seq() > asOfSeq) {
                return; // a gesture arrived after the verdict peek — never retro-clear it
            }
            if (state.compareAndSet(s, consumed(s, now))) {
                record(InputEvent.Kind.CONSUME, 0);
                return;
            }
        }
    }

    /**
     * Stamp-guarded mirror of vanilla's in-attack sprint clear — the pre-seq
     * ordering, kept for the contract record. NO-OPS when the wire holds a write
     * strictly newer than {@code asOf}. Since 2.6.0 it has no production caller:
     * a verdict without wire provenance no longer spends the wire engagement at
     * all (the stamp guard is same-tick-blind — a START arriving the same tick
     * as the verdict mint was retro-eaten; the seq guard replaced it for
     * wire-peeked verdicts in 2.5.1 and the non-wire consume was retired
     * outright in 2.6.0).
     */
    public void onServerClear(TickStamp asOf) {
        TickStamp now = clock.current();
        for (;;) {
            State s = state.get();
            if (s.lastWrite().known() && asOf.known() && s.lastWrite().value() > asOf.value()) {
                return; // a newer wire write wins — never retro-clear a later re-engage
            }
            if (state.compareAndSet(s, consumed(s, now))) {
                record(InputEvent.Kind.CONSUME, 0);
                return;
            }
        }
    }

    /**
     * The unconditional, retro-clearing form.
     *
     * @deprecated Superseded by the guarded forms (2.4.1). This form clears
     *     sprint/freshness with NO guard against a newer wire write, so a
     *     re-engage START arriving in the deferred-EDBEE window {@code [T, T+2]}
     *     was retroactively destroyed and the next hit shipped with no sprint
     *     extra (F2). Retained for the pre-guard behaviour only.
     */
    @Deprecated
    public void onServerClear() {
        TickStamp now = clock.current();
        state.updateAndGet(s -> consumed(s, now));
        record(InputEvent.Kind.CONSUME, 0);
    }

    /** The shared consume transition: spend the engagement, open the spend latch. */
    private static State consumed(State s, TickStamp now) {
        return new State(true, false, false, s.clientSprinting(), now, now, s.seq(),
                s.keyIntent(), s.lastSprintingAt(), s.lastServerSprinting(), s.startsSeen(),
                s.starvedConsume() || s.startsSeen() == 0, false);
    }

    /**
     * A block engagement re-armed sprint — the reconstructed era gesture (2.6.0,
     * one hit per reset; the owner's directive supersedes the 2.5.1 sticky
     * held-chain). Called by the always-on block door on the block right-click,
     * gated there on {@link #blockReArmEligible} — a sanctioned cross-thread
     * writer (the attacker's own region thread, atomic under the CAS). It is
     * EXACTLY a START-shaped gesture: arms {@code sprinting}+{@code armed},
     * raises the raw client flag, closes the spend latch, bumps {@code seq} —
     * and rings {@code BLOCK_RESET} so the trail tells a block re-arm from a
     * wire START. The ordinary engagement spend scopes the grant to ONE hit; an
     * unspent re-arm survives the block release (era-exact — the era client's
     * release re-engage is what re-armed the bonus, so a block-tap earns the
     * same single fresh hit a w-tap does).
     */
    public void onBlockSprintReset() {
        TickStamp now = clock.current();
        state.updateAndGet(s -> new State(true, true, true, true, TickStamp.NO_TICK, now,
                s.seq() + 1, s.keyIntent(), now, s.lastServerSprinting(), s.startsSeen(),
                s.starvedConsume(), s.adoptBlockedNoted()));
        record(InputEvent.Kind.BLOCK_RESET, 0);
    }

    /**
     * The client released an item use — RELEASE_USE_ITEM, observed on the
     * connection's own netty thread. Pure trail evidence since 2.6.0: the block
     * re-arm is a one-shot gesture spent by the hit's consume, so there is no
     * held-chain state left to end at the release boundary (the 2.5.1 sticky
     * reset this used to drop is retired), and an unspent re-arm deliberately
     * survives the release (era-exact — see {@link #onBlockSprintReset}). The
     * always-inbound RELEASE packets of any other item use (eating, drawing a
     * bow) ring the same evidence and touch nothing.
     */
    public void onBlockReleased() {
        record(InputEvent.Kind.RELEASE_USE_ITEM, 0);
    }

    /**
     * Re-seed from the published server flag after the wire has been quiet for
     * {@code quietTicks}, or when the wire has never been written (absent). A
     * fresh wire write inside the window is never overwritten by a stale-high
     * server flag — and neither is a spent engagement, ever. The sole reconcile
     * write to {@link #clientSprinting} is the {@code seen==false} SEED.
     *
     * <p>Branches, in priority order:</p>
     * <ol>
     *   <li><b>Seed</b>: an unseen wire adopts the live server flag into BOTH
     *       {@code sprinting} AND the raw {@code clientSprinting} (the flag at
     *       seed time IS the client's last transmitted state; its own pre-wire
     *       START set it), so a mid-play plugin-load/reload of an
     *       already-sprinting player keeps its first-hit bonus and its blockhit
     *       gate. The seed is not fresh — no re-engage happened.</li>
     *   <li><b>Adopt</b>: after {@code quietTicks} of wire silence the server's
     *       own flag wins a disagreement — EXCEPT adopt-TRUE while the spend
     *       latch is open ({@code clearedAt} known: a bonus hit consumed the
     *       engagement and no client gesture followed). A held-W modern client's
     *       server flag stays true forever (its STOP never comes — vanilla's own
     *       clear lives inside the cancelled ATTACK), so re-adopting it would
     *       resurrect the engagement the hit just spent: one engagement, one
     *       sprint knock. Adopt-FALSE (a genuine external un-sprint) is never
     *       blocked. An applied adopt resets {@code clearedAt}. The blocked
     *       branch is ringed ONCE per latch episode ({@code ADOPT_BLOCKED}).</li>
     *   <li><b>The falling-edge failsafe</b> (2.6.0): whenever the published flag
     *       transitions true→false while the latch is open, the latch closes
     *       ({@code SERVER_FALL}) — a client STOP provably reached vanilla even
     *       if this tap never saw it (a proxy/translation environment), so a
     *       missed gesture degrades to ordinary adoption instead of bricking the
     *       session's sprint knocks. It only closes; it never arms, and it never
     *       touches the wire's own sprint view.</li>
     * </ol>
     */
    public void reconcile(boolean serverSprinting, TickStamp now, int quietTicks) {
        for (;;) {
            State s = state.get();
            boolean fallingEdge = s.lastServerSprinting() == Boolean.TRUE && !serverSprinting
                    && s.clearedAt().known();
            InputEvent.Kind kind;
            State next;
            if (!s.seen()) {
                next = new State(true, serverSprinting, false, serverSprinting,
                        s.clearedAt(), now, s.seq() + 1, s.keyIntent(),
                        serverSprinting ? now : s.lastSprintingAt(), serverSprinting,
                        s.startsSeen(), s.starvedConsume(), s.adoptBlockedNoted());
                kind = InputEvent.Kind.SEED;
            } else if (s.sprinting() != serverSprinting
                    && s.lastWrite().known() && now.known()
                    && now.value() - s.lastWrite().value() >= quietTicks) {
                if (serverSprinting && s.clearedAt().known()) {
                    // The spend latch: a bonus hit consumed this engagement and no
                    // client gesture followed, so the still-high server flag IS the
                    // spent engagement, not a new grant. The latch — not seq — is
                    // the guard, deliberately: observations bump no gesture seq,
                    // while only START/STOP/block re-arm/adopt/SERVER_FALL reset
                    // clearedAt.
                    if (s.adoptBlockedNoted()) {
                        return; // already noted this episode — no churn, no ring flood
                    }
                    next = new State(s.seen(), s.sprinting(), s.armed(), s.clientSprinting(),
                            s.clearedAt(), s.lastWrite(), s.seq(), s.keyIntent(),
                            s.lastSprintingAt(), true, s.startsSeen(), s.starvedConsume(), true);
                    kind = InputEvent.Kind.ADOPT_BLOCKED;
                } else {
                    next = new State(true, serverSprinting, s.armed(), s.clientSprinting(),
                            TickStamp.NO_TICK, now, s.seq() + 1, s.keyIntent(),
                            serverSprinting ? now : s.lastSprintingAt(), serverSprinting,
                            s.startsSeen(), s.starvedConsume(), false);
                    kind = serverSprinting ? InputEvent.Kind.ADOPT_TRUE : InputEvent.Kind.ADOPT_FALSE;
                }
            } else if (fallingEdge) {
                // No adoptable disagreement, but the published flag fell while the
                // latch was open: close it. The wire's own sprint view is
                // untouched — this is a latch key, not a sprint write.
                next = new State(s.seen(), s.sprinting(), s.armed(), s.clientSprinting(),
                        TickStamp.NO_TICK, s.lastWrite(), s.seq(), s.keyIntent(),
                        s.lastSprintingAt(), false, s.startsSeen(), s.starvedConsume(), false);
                kind = InputEvent.Kind.SERVER_FALL;
            } else {
                if (equalFlags(s.lastServerSprinting(), serverSprinting)) {
                    return; // nothing to observe — the common per-movement-packet path
                }
                next = new State(s.seen(), s.sprinting(), s.armed(), s.clientSprinting(),
                        s.clearedAt(), s.lastWrite(), s.seq(), s.keyIntent(),
                        s.lastSprintingAt(), serverSprinting, s.startsSeen(), s.starvedConsume(),
                        s.adoptBlockedNoted());
                kind = null; // a rising/quiet edge with nothing to act on — tracked, not ringed
            }
            if (state.compareAndSet(s, next)) {
                if (kind != null) {
                    record(kind, 0);
                }
                return;
            }
        }
    }

    private static boolean equalFlags(Boolean previous, boolean current) {
        return previous != null && previous == current;
    }

    /**
     * The registration-time verdict (never null; falls back to the seeded
     * state): the plain arrival-order wire view — {@code sprinting} and the
     * armed freshness, stamped with the gesture {@code seq} the deferred consume
     * guards against. Since 2.6.0 there is no block override here: the block
     * re-arm IS a gesture ({@link #onBlockSprintReset}), so the wire view
     * already carries it, and the hit that spends it spends it whole.
     */
    public SprintVerdict verdictAt(TickStamp now) {
        State s = state.get();
        return new SprintVerdict(s.sprinting(), s.armed(), now, s.seq());
    }

    /**
     * Whether a 1.7/1.8 block engagement may (re-)arm the sprint bonus NOW — the
     * always-on block door consults this instead of the raw
     * {@link #clientSprinting} flag alone. Passes when the raw client flag is up
     * (the ORIGINAL gate, unchanged), OR when the PLAYER_INPUT sprint KEY is held
     * ({@code keyIntent} TRUE) and the wire sprinted within
     * {@link #ERA_BLOCKHIT_RECENCY_TICKS}: the one case a genuine STOP crosses
     * for a key-holder — a full-charge / spoofed hit landing while item-use
     * blocked the same-tick re-arm — where the era block-hitter must keep
     * re-arming through block cycles. A stationary defensive ctrl-holder is
     * excluded by the recency window. UNKNOWN keyIntent ({@code null} — every
     * pre-1.21.2 / Via / packetless client) collapses this to the raw-flag gate
     * exactly, so defaults stay zero-touch.
     */
    public boolean blockReArmEligible() {
        State s = state.get();
        return s.clientSprinting() || recentlySprintedWithKeyHeld(s, clock.current());
    }

    /**
     * The PLAYER_INPUT sprint-key recency corroborator: the sprint KEY is held
     * ({@code keyIntent} TRUE, never {@code null}/UNKNOWN and never FALSE) AND
     * the wire was last left sprinting no more than
     * {@link #ERA_BLOCKHIT_RECENCY_TICKS} ago. Widens the block-hit entry gate
     * only; nothing else reads it.
     */
    private static boolean recentlySprintedWithKeyHeld(State s, TickStamp now) {
        return s.keyIntent() == Boolean.TRUE
                && s.lastSprintingAt().known() && now.known()
                && now.value() - s.lastSprintingAt().value() <= ERA_BLOCKHIT_RECENCY_TICKS;
    }

    /**
     * The RAW client sprint flag — set only by {@link #onSprintStart}/{@link
     * #onSprintStop}, never by a server clear, and by {@link #reconcile} in ONE
     * place only (the seed). This is the only signal that survives the wire's
     * own post-hit clear, so the block door gates on it to avoid a phantom bonus
     * on a stationary defensive block (era-accuracy contract) — which is what
     * keeps the block-hit a valid re-gesture for a spent engagement.
     */
    public boolean clientSprinting() {
        return state.get().clientSprinting();
    }

    /**
     * Whether an engagement has been consumed on a ledger that had NEVER seen a
     * wire START at that point — the dead-feed alarm (a proxy/translation layer
     * or protocol drift starving the tap while the server still tracks sprint).
     * Sticky until the first real START proves the feed alive. The journal's
     * {@code note=starved}.
     */
    public boolean starved() {
        return state.get().starvedConsume();
    }

    /** A snapshot of the diagnostic ring, oldest first — the journal trail and the tester read. */
    public List<InputEvent> trail() {
        synchronized (ring) {
            return new ArrayList<>(ring);
        }
    }

    /** Appends to the ring AFTER the state transition won its CAS — never inside the retry loop. */
    private void record(InputEvent.Kind kind, int bits) {
        synchronized (ring) {
            ring.addLast(new InputEvent(kind, ++eventSeq, clock.current(), bits));
            if (ring.size() > RING_CAPACITY) {
                ring.removeFirst();
            }
        }
    }
}
