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
 * <p><b>One engagement, one sprint knock</b> (the 2.5.1 arming contract; the
 * owner's directive — supersedes the 2.5.0 post-clear re-arm). Mental does not
 * defer a {@code player.setSprinting(false)} after a bonus hit: on 1.21.2+ that
 * server-flag write is ECHOED to the attacker's own client, which adopts it,
 * drops its local sprint, and confirms with one STOP_SPRINTING — latching
 * {@link #clientSprinting} false with no START ever returning (the modern-client
 * sprint latch; never re-add it). Only the WIRE clear ({@link #onServerClear})
 * remains, and it CONSUMES the engagement: {@code sprinting} and {@code armed}
 * drop, and {@code clearedAt} opens the SPEND LATCH. Nothing re-arms
 * automatically — a modern client holding W sends exactly ONE START per
 * engagement (at spam cadence its local flag never drops, so it never
 * re-engages), and that engagement has been spent. Re-arming takes a
 * client-expressed re-gesture: a wire STOP→START cycle (w-tap, s-tap, a GUI
 * open), the sword-block re-arm ({@link #onBlockSprintReset}), a seed of an
 * unseen wire, or a server-granted adopt while no consume is outstanding. This
 * is the measured era server contract — real 1.8.9 consumed the flag inside
 * every bonus attack and re-armed only on a client START: a no-w-tap double
 * flew 7.2 blocks where a w-tap double flew 11.4 (era-wire measurements).
 * While the latch is open {@link #reconcile} must not re-adopt the stale-high
 * server flag (a held-W client's flag stays true forever — vanilla's own clear
 * lives inside the cancelled ATTACK); the latch closes on any client
 * START/STOP, the block re-arm, and an applied adopt, so a genuine re-gesture
 * always reopens ordinary adoption.</p>
 *
 * <p><b>State is one immutable snapshot swapped by reference</b> (the codebase
 * idiom). The primary owner is the connection's netty thread (START/STOP,
 * reconcile, verdict reads), but the desk's post-hit clear runs on the victim's
 * region thread and {@code SwordBlockingUnit} reads {@link #clientSprinting} on
 * the attacker's — so the fields are read/written across threads. Every mutation
 * is a CAS over the whole {@link State}, giving each read a coherent, atomic view
 * (no torn mix), and each write happens-before the next read. The
 * {@link #clientSprinting} flag tracks the RAW client sprint packets ONLY — it is
 * never touched by {@link #onServerClear(TickStamp)}, and {@link #reconcile}
 * writes it in ONE place: the {@code seen==false} SEED, which adopts the server
 * flag as the client's last transmitted sprint state (a mid-play
 * plugin-load/reload seeds an already-sprinting player faithfully; the ADOPT
 * branch stays clientSprinting-blind). So it
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
     * {@code clearedAt} is the SPEND LATCH — the tick a post-hit
     * {@link #onServerClear} last consumed the engagement (its {@code NO_TICK}
     * sentinel means "no consume outstanding"; while known, {@link #reconcile}
     * never adopts a stale-high server flag — reset by any client START/STOP, the
     * block re-arm, and an applied adopt); {@code seen} gates
     * reconcile seeding; {@code lastWrite} is the reconcile quiet clock; {@code seq}
     * counts WIRE WRITES in arrival order — client START/STOP, the block-hit re-arm,
     * and a reconcile seed/adopt each bump it, so it IS this wire's era queue
     * program order. {@link #onBlockReleased}, {@link #onKeyIntent} and
     * {@link #onServerClear} do NOT bump: a release only drops the sticky
     * {@code blockReset} (deliberately not a sprint write, matching its
     * {@code lastWrite} exemption), a key-intent sample is an OBSERVATION the
     * clear carries through unchanged (bumping here handed any PLAYER_INPUT edge
     * a veto over the deferred consume — see {@link #onKeyIntent}), and the clear
     * is the CONSUMER of the ordering, never a producer. {@code keyIntent} is the
     * raw PLAYER_INPUT sprint KEY
     * intent (1.21.2+, the 0x40 flag; {@code null} = UNKNOWN, the INITIAL value and
     * the value for every client that never sends it), written ONLY by
     * {@link #onKeyIntent} (which bumps NEITHER {@code seq} NOR {@code lastWrite});
     * clears and reconciles never touch it. {@code lastSprintingAt} is the tick the
     * wire was LAST left sprinting (any transition to {@code sprinting==true}
     * refreshes it); together with {@code keyIntent} it is the corroborator the two
     * SWORD_BLOCKING block-hit gates read, and nothing else.
     */
    private record State(boolean seen, boolean sprinting, boolean armed,
                         boolean clientSprinting, boolean blockReset, TickStamp clearedAt,
                         TickStamp lastWrite, long seq, Boolean keyIntent,
                         TickStamp lastSprintingAt) {}

    /**
     * The recency window for the PLAYER_INPUT sprint-key corroborator: a held
     * sprint KEY ({@code keyIntent} TRUE) only widens a block-hit gate when the wire
     * recorded sprinting within this many ticks. 20 ticks ≈ 1s — long enough to
     * carry an era block-hitter through the one blocked-tick STOP a key-holder ever
     * crosses and its subsequent re-arm cycles, short enough to exclude a stationary
     * defensive ctrl-holder (no sprint in the last second ⇒ defending, not
     * block-hitting mid-combo — the phantom-bonus guard). A 1-second "still in the
     * fight" heuristic, not era-tuned wire timing.
     */
    public static final int ERA_BLOCKHIT_RECENCY_TICKS = 20;

    private static final State INITIAL =
            new State(false, false, false, false, false, TickStamp.NO_TICK, TickStamp.NO_TICK, 0L,
                    null, TickStamp.NO_TICK);

    private final TickClock clock;
    private final AtomicReference<State> state = new AtomicReference<>(INITIAL);

    public SprintWire(TickClock clock) {
        this.clock = clock;
    }

    /**
     * A wire START: sets the flag, arms freshness, and raises the raw client flag
     * (the re-engage is the w-tap). Resets {@code clearedAt} — a real START is
     * the genuine re-gesture that closes the spend latch and arms a new
     * engagement.
     */
    public void onSprintStart() {
        TickStamp now = clock.current();
        state.updateAndGet(s -> new State(true, true, true, true, s.blockReset(), TickStamp.NO_TICK, now, s.seq() + 1,
                s.keyIntent(), now)); // sprinting=true ⇒ lastSprintingAt = now
    }

    /**
     * A wire STOP: drops the flag and the raw client flag — armed freshness survives
     * the release half. Resets {@code clearedAt} — the client expressed un-sprint
     * (the release half of a w-tap closes the spend latch; the re-press arms the
     * new engagement).
     */
    public void onSprintStop() {
        TickStamp now = clock.current();
        state.updateAndGet(s -> new State(true, false, s.armed(), false, s.blockReset(), TickStamp.NO_TICK, now, s.seq() + 1,
                s.keyIntent(), s.lastSprintingAt())); // sprinting=false ⇒ lastSprintingAt carries
    }

    /**
     * Records the client's raw SPRINT KEY intent from a PLAYER_INPUT packet (1.21.2+,
     * the 0x40 flag = {@code keySprint.isDown()}): TRUE for a ctrl/toggle holder even
     * while standing or item-use-blocked, FALSE for a double-tap-W sprinter even while
     * sprinting. It is NEVER a verdict source — {@link #verdictAt} still derives
     * {@code sprinting} only from the wire — only a recency corroborator for the two
     * SWORD_BLOCKING block-hit gates ({@link #blockReArmEligible} and the
     * {@code verdictAt} blockReset override); its ABSENCE (INITIAL {@code null}
     * keyIntent — every pre-1.21.2 / Via / packetless client) leaves both gates
     * byte-identical to before, so defaults stay zero-touch.
     *
     * <p>NOT a wire write: it bumps neither {@code seq} nor {@code lastWrite}. The
     * seq guard exists so a deferred {@link #onServerClear(long)} cannot retro-eat
     * a later GESTURE (a re-engage START) — but a key-intent sample is an
     * observation, not a gesture, and the clear carries {@code keyIntent} through
     * unchanged, so there is nothing for it to retro-eat. When this DID bump seq
     * (24d9990, reverted in the 2.5.1 verification round), any PLAYER_INPUT edge —
     * the packet fires on ANY of the 7 input bits flipping: a jump, a strafe
     * reverse, a sneak — landing in the 1–2-tick deferred-clear window vetoed the
     * consume, so a bunny-hopping W-holder's engagement was never spent and the
     * per-hit sprint knock survived the spend-latch fix. {@code lastWrite} stays
     * untouched for the same reason it always did: it is the reconcile quiet clock
     * for SPRINT adoption, and a key-state change is not a sprint-truth write.
     * Everything else — {@code sprinting}, {@code armed}, the raw client flag,
     * {@code blockReset}, {@code clearedAt} and {@code lastSprintingAt} — carries
     * unchanged. Called on the connection's netty thread; the CAS keeps it atomic
     * against the cross-thread readers.</p>
     */
    public void onKeyIntent(boolean intent) {
        state.updateAndGet(s -> new State(s.seen(), s.sprinting(), s.armed(), s.clientSprinting(),
                s.blockReset(), s.clearedAt(), s.lastWrite(), s.seq(), intent, s.lastSprintingAt()));
    }

    /**
     * Stamp-guarded mirror of vanilla's in-attack sprint clear, applied when the
     * desk reports an accepted bonus hit whose verdict carries NO wire provenance
     * ({@code wtapConsultWire=false} fallback and non-melee mints — a wire-peeked
     * verdict uses {@link #onServerClear(long)}, the authoritative arrival-order
     * guard). The flag drops, the freshness the hit used is spent, and
     * {@code clearedAt} opens the spend latch: the engagement is consumed, and
     * only a client-expressed re-gesture arms the next one. The raw
     * {@link #clientSprinting} flag is left ALONE — only START/STOP packets
     * write it.
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
            // clearedAt = now opens the spend latch (reconcile never re-adopts the
            // stale-high server flag until a client gesture closes it).
            return new State(true, false, false, s.clientSprinting(), s.blockReset(), now, now, s.seq(), s.keyIntent(), s.lastSprintingAt());
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
     * raw clientSprinting flag survive as ever, and {@code clearedAt} opens the
     * spend latch (the engagement is consumed); an applied clear still
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
            return new State(true, false, false, s.clientSprinting(), s.blockReset(), now, now, s.seq(), s.keyIntent(), s.lastSprintingAt());
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
        state.updateAndGet(s -> new State(true, false, false, s.clientSprinting(), s.blockReset(), now, now, s.seq(), s.keyIntent(), s.lastSprintingAt()));
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
        state.updateAndGet(s -> new State(true, true, true, true, true, TickStamp.NO_TICK, now, s.seq() + 1,
                s.keyIntent(), now)); // block re-arm leaves sprinting=true ⇒ lastSprintingAt = now
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
                        s.clearedAt(), s.lastWrite(), s.seq(), s.keyIntent(), s.lastSprintingAt())
                : s);
    }

    /**
     * Re-seed from the published server flag after the wire has been quiet for
     * {@code quietTicks}, or when the wire has never been written (absent). A
     * fresh wire write inside the window is never overwritten by a stale-high
     * server flag — and neither is a spent engagement, ever. The sole reconcile
     * write to {@link #clientSprinting} — the {@code seen==false} SEED, which
     * adopts the server flag as the client's last transmitted sprint state; every
     * other reconcile path leaves it packet-only.
     *
     * <p>Two branches, in priority order:</p>
     * <ol>
     *   <li><b>Seed</b>: an unseen wire adopts the live server flag into BOTH
     *       {@code sprinting} AND the raw {@code clientSprinting} (the flag at seed
     *       time IS the client's last transmitted state; its own pre-wire START set
     *       it), so a mid-play plugin-load/reload of an already-sprinting player
     *       keeps its first-hit bonus and its blockhit gate. The seed is not
     *       fresh — no re-engage happened.</li>
     *   <li><b>Adopt</b>: after {@code quietTicks} of wire silence the server's own
     *       flag wins a disagreement — EXCEPT adopt-TRUE while the spend latch is
     *       open ({@code clearedAt} known: a bonus hit consumed the engagement and
     *       no client gesture followed). A held-W modern client's server flag
     *       stays true forever (its STOP never comes — vanilla's own clear lives
     *       inside the cancelled ATTACK), so re-adopting it would resurrect the
     *       engagement the hit just spent: one engagement, one sprint knock.
     *       Adopt-FALSE (a genuine external un-sprint) is never blocked. An
     *       applied adopt resets {@code clearedAt} — the server has
     *       authoritatively asserted its flag.</li>
     * </ol>
     */
    public void reconcile(boolean serverSprinting, TickStamp now, int quietTicks) {
        state.updateAndGet(s -> {
            if (!s.seen()) {
                // Seed clientSprinting = serverSprinting: the server flag at seed time IS
                // the client's last transmitted sprint state — the client's own pre-wire
                // START set it through vanilla's packet handler (the only client-driven
                // setter on 1.21.11, empirically verified). A mid-play plugin load/reload
                // creates the wire for an ALREADY-sprinting player; without this the seed
                // carried the INITIAL false, and the post-hit clear then found the raw flag
                // low, so the SwordBlockingUnit blockhit gate would not fire until a
                // genuine STOP/START edge — the player stuck on plain knockback despite
                // genuinely sprinting. The ADOPT branch below stays clientSprinting-blind:
                // it must never overwrite live client-action state.
                return new State(true, serverSprinting, false, serverSprinting, s.blockReset(),
                        s.clearedAt(), now, s.seq() + 1, s.keyIntent(),
                        serverSprinting ? now : s.lastSprintingAt()); // seed to sprinting ⇒ refresh lastSprintingAt
            }
            if (s.sprinting() != serverSprinting && s.lastWrite().known() && now.known()
                    && now.value() - s.lastWrite().value() >= quietTicks) {
                if (serverSprinting && s.clearedAt().known()) {
                    // The spend latch: a bonus hit consumed this engagement and no
                    // client gesture followed, so the still-high server flag IS the
                    // spent engagement, not a new grant. The latch — not seq — is the
                    // guard, deliberately: onKeyIntent bumps seq without being a
                    // gesture, while only START/STOP/block re-arm reset clearedAt.
                    return s;
                }
                return new State(true, serverSprinting, s.armed(), s.clientSprinting(), s.blockReset(),
                        TickStamp.NO_TICK, now, s.seq() + 1, s.keyIntent(),
                        serverSprinting ? now : s.lastSprintingAt()); // adopt-true ⇒ refresh lastSprintingAt
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
     *
     * <p>The block override also survives the SWORD_BLOCKING-scoped
     * {@code keyIntent} corroborator: a held sprint KEY that sprinted within
     * {@link #ERA_BLOCKHIT_RECENCY_TICKS} keeps the override alive when a genuine
     * blocked-tick STOP has lowered the raw client flag (see
     * {@link #recentlySprintedWithKeyHeld}). UNKNOWN keyIntent collapses this back to
     * the raw-flag test exactly, so a client that never sends PLAYER_INPUT is
     * unaffected.</p>
     */
    public SprintVerdict verdictAt(TickStamp now) {
        State s = state.get();
        boolean blockHeldReset = s.blockReset()
                && (s.clientSprinting() || recentlySprintedWithKeyHeld(s, now));
        boolean sprinting = blockHeldReset || s.sprinting();
        boolean fresh = blockHeldReset || s.armed();
        return new SprintVerdict(sprinting, fresh, now, s.seq());
    }

    /**
     * Whether a 1.7/1.8 sword-block engagement may (re-)arm the sprint bonus NOW —
     * the SWORD_BLOCKING block-hit gate {@code SwordBlockingUnit.resetSprintForBlock}
     * consults this instead of the raw {@link #clientSprinting} flag alone. Passes
     * when the raw client flag is up (the ORIGINAL gate, unchanged), OR when the
     * PLAYER_INPUT sprint KEY is held ({@code keyIntent} TRUE) and the wire sprinted
     * within {@link #ERA_BLOCKHIT_RECENCY_TICKS}: the one case a genuine STOP crosses
     * for a key-holder — a full-charge / spoofed hit landing while item-use blocked
     * the same-tick re-arm — where the era block-hitter must keep re-arming through
     * block cycles. A stationary defensive ctrl-holder is excluded by the recency
     * window (no sprint in the last second). UNKNOWN keyIntent ({@code null}, the
     * default for pre-1.21.2 / Via / packetless clients) collapses this to the
     * raw-flag gate exactly, so defaults stay zero-touch.
     */
    public boolean blockReArmEligible() {
        State s = state.get();
        return s.clientSprinting() || recentlySprintedWithKeyHeld(s, clock.current());
    }

    /**
     * The PLAYER_INPUT sprint-key recency corroborator: the sprint KEY is held
     * ({@code keyIntent} TRUE, never {@code null}/UNKNOWN and never FALSE) AND the
     * wire was last left sprinting no more than {@link #ERA_BLOCKHIT_RECENCY_TICKS}
     * ago. Widens the two SWORD_BLOCKING block-hit gates only; nothing else reads it.
     */
    private static boolean recentlySprintedWithKeyHeld(State s, TickStamp now) {
        return s.keyIntent() == Boolean.TRUE
                && s.lastSprintingAt().known() && now.known()
                && now.value() - s.lastSprintingAt().value() <= ERA_BLOCKHIT_RECENCY_TICKS;
    }

    /**
     * The RAW client sprint flag — set only by {@link #onSprintStart}/{@link
     * #onSprintStop}, never by a server clear, and by {@link #reconcile} in ONE
     * place only: the {@code seen==false} seed adopts the server flag as the
     * client's last transmitted state (the ADOPT branch never touches it). This is
     * the only signal that survives the wire's own post-hit clear, so block-hit re-arming
     * ({@code SwordBlockingUnit}) gates on it to avoid a phantom bonus on a
     * stationary defensive block (era-accuracy contract) — which is what keeps
     * the block-hit a valid re-gesture for a spent engagement.
     */
    public boolean clientSprinting() {
        return state.get().clientSprinting();
    }
}
