package me.vexmc.mental.kernel.delivery;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import me.vexmc.mental.kernel.delivery.HitTransaction.State;
import me.vexmc.mental.kernel.model.HitContext;
import me.vexmc.mental.kernel.model.HitId;
import me.vexmc.mental.kernel.model.JournalEntry;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.model.SprintVerdict;
import me.vexmc.mental.kernel.model.TickStamp;
import me.vexmc.mental.kernel.port.TickClock;

/**
 * One victim's delivery decisions (spec §3.4–§3.6). Single-writer (the victim's
 * session thread) for every method except {@link #submitFromWire}, which the
 * registering netty thread calls — the ONLY cross-thread entry, backed by an
 * atomic slot the owner drains at the start of each owning-thread operation.
 *
 * <p>The desk holds at most one pending decision (last-submitter-wins per victim
 * per tick — a supersede journals the displaced decision and carries any owed
 * velocity event to the newest) and writes the bounded per-victim journal — the
 * single "what did we actually ship" seam. Withdrawal is by exact {@link HitId};
 * there is no withdraw-all (B4).</p>
 */
public final class DeliveryDesk {

    /** The pre-resolution states — a decision still live and unrecorded. */
    private static final EnumSet<State> LIVE =
            EnumSet.of(State.REGISTERED, State.PLANNED, State.PRE_SENT, State.PINNED);

    private final int victimEntityId;
    private final TickClock clock;
    private final int journalCapacity;
    private final JournalObserver observer;
    private final ArrayDeque<JournalEntry> journal = new ArrayDeque<>();

    /**
     * The netty→owner hand-off slot: pre-sent/pinned transactions ahead of their
     * damage tasks, an immutable arrival-ordered chain. Registration runs on the
     * ATTACKER's netty thread, so two attackers push from two different threads,
     * and the owner may not drain between pushes — the old last-write-wins
     * reference silently discarded the earlier transaction (never journaled, its
     * vanilla duplicate never valve-consumed). The CAS push keeps every arrival;
     * the owner drains the whole chain in arrival order so an overtaken
     * transaction is journaled "superseded" through the one supersede path.
     */
    private final AtomicReference<Wire> wireSlot = new AtomicReference<>();

    private HitTransaction pending;
    private KnockbackVector pendingVector;
    private boolean vectorSubmitted;
    private boolean awaiting;

    /** The pre-F9 arity: a desk with no journal observer (delegates to {@link JournalObserver#NONE}). */
    public DeliveryDesk(int victimEntityId, TickClock clock, int journalCapacity) {
        this(victimEntityId, clock, journalCapacity, JournalObserver.NONE);
    }

    public DeliveryDesk(int victimEntityId, TickClock clock, int journalCapacity, JournalObserver observer) {
        this.victimEntityId = victimEntityId;
        this.clock = clock;
        this.journalCapacity = journalCapacity;
        this.observer = observer;
    }

    /** Netty entry: a PRE_SENT/PINNED transaction arriving ahead of its damage task. */
    public void submitFromWire(HitTransaction tx) {
        wireSlot.getAndUpdate(prior -> new Wire(tx, prior));
    }

    /** Owning-thread entry: a feature submits/replaces the vector for a transaction. */
    public void submit(HitTransaction tx, KnockbackVector vector) {
        drainWire();
        replacePending(tx, vector);
    }

    /** Withdrawal is by exact id — there is deliberately NO withdraw-all (B4). */
    public void withdraw(HitId id) {
        drainWire();
        if (pending != null && pending.context().id().equals(id)) {
            clearDecision();
        }
    }

    /**
     * Withdraw a pending decision that a fresh transaction supersedes — the
     * blocked-knock redelivery. Unlike {@link #withdraw(HitId)}, which is silent
     * for genuine cancels/yields, this JOURNALS the withdrawal (the reason,
     * referencing {@code supersededBy}) so a blocked hit stays correlatable to the
     * fresh id that carries its SHIP. A no-op when {@code id} is not the live
     * pending (e.g. a region-path original that was never submitted to the desk).
     */
    public void withdrawSuperseded(HitId id, String reason, HitId supersededBy) {
        drainWire();
        if (pending != null && pending.context().id().equals(id) && LIVE.contains(pending.state())) {
            pending.suppressed(reason);
            record(pending, null, false, reason + " -> " + supersededBy.value(), "superseded");
            clearDecision();
        }
    }

    /**
     * Journal a bare drop for a transaction that never reached a resolution — the
     * blocked-knock redelivery whose owning-thread task retired before it could
     * submit. It never became pending, so there is no state machine transition;
     * the note is correlatable and never a valve (replaces a silent {@code () ->
     * {}} retired fallback).
     */
    public void journalDrop(HitTransaction tx, String reason) {
        appendJournal(tx.context(), new JournalEntry(
                tx.context().id(), tx.context().source(), null, false, reason,
                clock.current(), tx.paceFactor(), tx.comboFactor(), captureOf(tx, "drop")));
    }

    /**
     * The damage pass marks that the imminent velocity event resolves the pending
     * decision. It never resurrects a withdrawn or absent decision — the pending
     * transaction is established only by {@link #submit}/{@link #submitFromWire};
     * a resolve with nothing pending passes through (item 1). The {@code tx}
     * argument is deliberately unchecked against the pending: the arm declares that
     * a velocity event is OWED to this desk (the caller's damage pass marked hurt);
     * if a newer submission supersedes before the event lands, the debt carries and
     * the newest decision answers it — the era's one-stamp-latest-fields wire.
     */
    public void awaitVelocityEvent(HitTransaction tx) {
        drainWire();
        awaiting = true;
    }

    /** Non-consuming view for the EntityKnockbackEvent mirror. */
    public KnockbackVector mirrorView() {
        return pendingVector;
    }

    /** The formula vector KnockbackApplyEvent exposes, or null if nothing pending. */
    public KnockbackVector pendingFormula() {
        return pending == null ? null : pendingVector;
    }

    /** The pending decision's compute-once context (attacker, hit id, source), or null. */
    public me.vexmc.mental.kernel.model.HitContext pendingContext() {
        return pending == null ? null : pending.context();
    }

    /**
     * The still-live vector for exactly {@code id}, or null if that decision was
     * already resolved (a natural velocity event shipped it), superseded, or
     * dropped. Non-consuming — the no-natural-event fallback (rod/thrown ensure)
     * reads it to decide whether it still needs to trigger delivery, without
     * disturbing the decision (which it then re-submits fresh so the wire ships
     * the full stamp rather than a physics-decayed setVelocity).
     */
    public KnockbackVector pendingVectorFor(HitId id) {
        drainWire();
        if (pending != null && pending.context().id().equals(id) && LIVE.contains(pending.state())) {
            return vectorSubmitted ? pendingVector : pending.carried();
        }
        return null;
    }

    /**
     * True only when the pending decision is exactly {@code id}, still LIVE, carries
     * a submitted vector, AND has been armed for the victim's velocity event
     * ({@link #submit} followed by {@link #awaitVelocityEvent}) — a decision genuinely
     * awaiting delivery. This is the region-path no-velocity-event net's gate (the
     * session's {@code ensureStrandedPacketlessMelee}): a FRESH melee submits its
     * vector and arms the await, so a still-stranded one reads {@code true} and the
     * net ships the era knock a packetless victim's late/absent velocity event never
     * did. An era-silent BLOCKED difference hit (a partially-blocked hit landing
     * mid-invulnerability) fires no vanilla velocity event and must ship nothing, so
     * the knockback unit submits its vector but deliberately leaves the await
     * UNARMED; it reads {@code false} here and the net leaves it for the sweep to
     * drop, so the era knock stays withheld (no knock, no flinch). That is the ONLY
     * unarmed melee class — a hit whose frozen immune read merely LOOKS mid-invuln
     * (a legal boundary combo hit) is armed like any fresh hit, because vanilla's
     * live counter accepts it and its genuine velocity event must resolve to the
     * submitted stamp. Distinct from {@link #pendingVectorFor}, which
     * a submitted-but-unarmed decision (a rod self-launch that ensures itself inline)
     * still answers non-null. Also {@code false} once the decision resolved, was
     * withdrawn/superseded, or is not this desk's pending. Non-consuming.
     */
    public boolean awaitingDeliveryFor(HitId id) {
        drainWire();
        return awaiting
                && pending != null
                && pending.context().id().equals(id)
                && LIVE.contains(pending.state())
                && vectorSubmitted;
    }

    /** Resolve at PlayerVelocityEvent time with the post-listener api velocity. */
    public Directive resolve(double apiX, double apiY, double apiZ) {
        drainWire();
        if (!awaiting || pending == null) {
            // 1. Foreign velocity — never journaled, never ledger-recorded.
            return new Directive(Directive.Action.PASS_THROUGH, null, null);
        }
        HitTransaction tx = pending;
        KnockbackVector formula = vectorSubmitted ? pendingVector : tx.carried();
        Directive directive;
        if (formula == null) {
            // 2. Legacy resistance roll: cancel the event, suppress, journal.
            tx.suppressed("resistance-roll");
            record(tx, null, false, "resistance-roll", "cancel");
            directive = new Directive(Directive.Action.CANCEL_EVENT, null, null);
        } else if (tx.state() == State.PRE_SENT) {
            KnockbackVector carried = tx.carried();
            boolean unmodified = apiX == formula.x() && apiY == formula.y() && apiZ == formula.z();
            if (unmodified) {
                // 3. Unmodified pre-send: ship the wire vector and arm the valve.
                tx.adopted();
                record(tx, carried, true, null, "ship-valve");
                directive = new Directive(Directive.Action.SHIP_AND_ARM_VALVE,
                        carried, ValvePayload.of(victimEntityId, carried));
            } else {
                // 4. A third party modified the event: ship the correction, no valve.
                KnockbackVector modified = new KnockbackVector(apiX, apiY, apiZ);
                tx.adopted();
                record(tx, modified, false, null, "ship-corrected");
                directive = new Directive(Directive.Action.SHIP, modified, null);
            }
        } else if (tx.state() == State.PINNED) {
            // 5. Pinned: ship normally, never a valve (B4). A wire-failed
            //    downgrade (BurstSender UNSENDABLE) carries its note into the
            //    journal so a refused burst is visible in one read.
            KnockbackVector carried = tx.carried();
            tx.adopted();
            record(tx, carried, false, tx.deliveryNote(), "ship-pinned");
            directive = new Directive(Directive.Action.SHIP, carried, null);
        } else if (LIVE.contains(tx.state())) {
            // A live REGISTERED/PLANNED decision (vanilla path): ship the formula,
            // no valve. Reachable via adopted() from REGISTERED/PLANNED.
            tx.adopted();
            record(tx, formula, false, null, "ship-formula");
            directive = new Directive(Directive.Action.SHIP, formula, null);
        } else {
            // F6 hardening: a RECORDED/terminal pending reached resolve. The Folia
            // counter/region skew can let the session sweep record+drop this tx just
            // before its deferred damage re-submits it; adopted() would throw INSIDE
            // the PlayerVelocityEvent handler ("Could not pass event
            // PlayerVelocityEvent") — vanilla's vector ships plus a doubled wire.
            // Journal a distinct note and pass the event through untouched; NEVER
            // transition a terminal tx.
            appendJournal(tx.context(), new JournalEntry(
                    tx.context().id(), tx.context().source(), null, false, "late-resolve-recorded",
                    clock.current(), tx.paceFactor(), tx.comboFactor(), captureOf(tx, "late-resolve")));
            directive = new Directive(Directive.Action.PASS_THROUGH, null, null);
        }
        clearDecision();
        return directive;
    }

    /**
     * Session-tick sweep. A still-awaiting (LIVE) transaction is DROPPED only once
     * it is at least TWO ticks older than {@code now}: the one-tick margin absorbs
     * Folia's counter/region phase skew of ±1 (F6). The sweep and the deferred
     * damage task read the same {@link me.vexmc.mental.kernel.port.TickClock}, but a
     * region-vs-global counter can be a tick apart, so an age-1 drop could race the
     * velocity event and ship vanilla's vector plus a doubled wire. An
     * already-RESOLVED-but-unrecorded pending (e.g. retracted by the retire path)
     * has no velocity event coming, so it is journaled immediately.
     */
    public void sweep(TickStamp now) {
        sweep(now, false);
    }

    /**
     * As {@link #sweep(TickStamp)}, but a CONNECTED victim's still-awaiting melee is
     * NEVER time-dropped (2.4.6 vanilla-knockback leak fix). A connected victim's
     * genuine {@code PlayerVelocityEvent} is the sole delivery authority for its
     * melee and can land a tick or two late under Folia region/counter skew or a
     * lagging main thread; dropping the pending early as {@code no-velocity-event}
     * lets that late event {@code resolve} against nothing (or an F6-recorded
     * terminal) and PASS_THROUGH vanilla's OWN velocity — which for an airborne
     * combo hit is vanilla's KEPT falling-y, i.e. a DOWNWARD knock infiltrating (the
     * preset-wide symptom). Holding it means the late event always finds the pending
     * and ships Mental's era vector, overriding vanilla. A genuinely orphaned
     * connected pending is superseded by the next hit ({@link #replacePending}); only
     * a PACKETLESS victim — no velocity event ever coming — is dropped here, in step
     * with the stranded-packetless net's own {@code domains.has} gate.
     */
    public void sweep(TickStamp now, boolean victimConnected) {
        drainWire();
        if (pending == null) {
            return;
        }
        TickStamp registeredAt = pending.context().registeredAt();
        if (!registeredAt.known() || !now.known() || registeredAt.value() >= now.value()) {
            return; // same tick (its velocity event may still come) or an unknown clock
        }
        if (LIVE.contains(pending.state())) {
            // A connected victim's velocity event is authoritative and merely late —
            // never time-drop it, or vanilla's velocity infiltrates on the late resolve.
            if (!victimConnected && now.value() - registeredAt.value() >= 2) {
                pending.dropped("no-velocity-event");
                record(pending, null, false, "no-velocity-event", "drop");
                clearDecision();
            }
            return; // age 1, or connected: hold for the (authoritative) velocity event
        }
        // Already resolved but not recorded — no velocity event is coming, so
        // journal it now (tick-causal, never a valve).
        record(pending, null, false, reasonFor(pending.state()), "sweep");
        clearDecision();
    }

    /** Ensure step for no-velocity-event sources: unresolved => Directive to setVelocity. */
    public Directive ensure(HitId id) {
        drainWire();
        if (pending != null && pending.context().id().equals(id) && LIVE.contains(pending.state())) {
            KnockbackVector vector = vectorSubmitted ? pendingVector : pending.carried();
            pending.ensured();
            record(pending, vector, false, null, "ensured");
            clearDecision();
            return new Directive(Directive.Action.SHIP, vector, null);
        }
        // Already resolved (idempotent) or not this desk's decision.
        return new Directive(Directive.Action.PASS_THROUGH, null, null);
    }

    public List<JournalEntry> journal() {
        return List.copyOf(journal);
    }

    /* ------------------------------------------------------------------ */

    private void drainWire() {
        Wire newestFirst = wireSlot.getAndSet(null);
        if (newestFirst == null) {
            return;
        }
        // Reverse to arrival order: each older arrival is superseded (journaled) by
        // the next, and the newest becomes the pending decision — the era's
        // latest-fields stamp.
        Wire arrivalOrder = null;
        for (Wire node = newestFirst; node != null; node = node.prior()) {
            arrivalOrder = new Wire(node.tx(), arrivalOrder);
        }
        for (Wire node = arrivalOrder; node != null; node = node.prior()) {
            replacePending(node.tx(), node.tx().carried());
        }
    }

    private void replacePending(HitTransaction tx, KnockbackVector vector) {
        if (pending != null && pending != tx && LIVE.contains(pending.state())) {
            // 6. Last-submitter-wins: the earlier decision is superseded + journaled.
            pending.suppressed("superseded");
            record(pending, null, false, "superseded", "superseded");
        }
        pending = tx;
        pendingVector = vector;
        vectorSubmitted = true;
        // `awaiting` is deliberately PRESERVED across the replace: it means "a
        // velocity event is owed to this desk" (an armed decision's damage pass
        // already marked hurt), not "the current pending was armed". The era wire
        // ships ONE tracker stamp per tick reflecting the LATEST server fields, so
        // the owed event must resolve against the newest decision. Resetting the
        // flag here let a superseded hit's own event fall to PASS_THROUGH and ship
        // vanilla's damage-pass velocity — base-only 0.4 with no sprint/enchant
        // extras (they live in the cancelled Player.attack), kept falling-y when
        // airborne: the close-range weak-knock leak. A supersede while nothing is
        // armed (era-silent chains) stays unarmed exactly as before; the debt is
        // cleared only by clearDecision (resolve / withdraw / sweep).
    }

    private void clearDecision() {
        pending = null;
        pendingVector = null;
        vectorSubmitted = false;
        awaiting = false;
    }

    /** Journals the transaction with its F9 resolution tag and marks it RECORDED (terminal). */
    private void record(HitTransaction tx, KnockbackVector shipped, boolean wireCarried,
                        String reason, String resolution) {
        appendJournal(tx.context(), new JournalEntry(
                tx.context().id(), tx.context().source(), shipped, wireCarried, reason,
                clock.current(), tx.paceFactor(), tx.comboFactor(), captureOf(tx, resolution)));
        tx.recorded();
    }

    /** Copies the transaction's F9 stamps + its context's sprint verdict into the entry's capture. */
    private static JournalEntry.Capture captureOf(HitTransaction tx, String resolution) {
        SprintVerdict sprint = tx.context().sprint();
        return new JournalEntry.Capture(
                sprint != null && sprint.sprinting(),
                sprint == null ? null : sprint.fresh(),
                tx.presend(), resolution, tx.geometry(), tx.profileName());
    }

    /** Appends a journal entry, evicts the oldest past capacity, then notifies the observer. */
    private void appendJournal(HitContext context, JournalEntry entry) {
        journal.addLast(entry);
        while (journal.size() > journalCapacity) {
            journal.removeFirst();
        }
        try {
            observer.journaled(context, entry);
        } catch (Throwable failure) {
            // A debug tap must never break the delivery core.
        }
    }

    private static String reasonFor(State state) {
        return switch (state) {
            case RETRACTED -> "retracted";
            case DROPPED -> "no-velocity-event";
            case SUPPRESSED -> "suppressed";
            default -> null;
        };
    }

    /** One wire arrival; {@code prior} chains the earlier un-drained arrivals (newest first). */
    private record Wire(HitTransaction tx, Wire prior) { }
}
