package me.vexmc.mental.kernel.delivery;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import me.vexmc.mental.kernel.delivery.HitTransaction.State;
import me.vexmc.mental.kernel.model.HitId;
import me.vexmc.mental.kernel.model.JournalEntry;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.model.TickStamp;
import me.vexmc.mental.kernel.port.TickClock;

/**
 * One victim's delivery decisions (spec §3.4–§3.6). Single-writer (the victim's
 * session thread) for every method except {@link #submitFromWire}, which the
 * registering netty thread calls — the ONLY cross-thread entry, backed by an
 * atomic slot the owner drains at the start of each owning-thread operation.
 *
 * <p>The desk holds at most one pending decision (last-submitter-wins per victim
 * per tick) and writes the bounded per-victim journal — the single "what did we
 * actually ship" seam. Withdrawal is by exact {@link HitId}; there is no
 * withdraw-all (B4).</p>
 */
public final class DeliveryDesk {

    /** The pre-resolution states — a decision still live and unrecorded. */
    private static final EnumSet<State> LIVE =
            EnumSet.of(State.REGISTERED, State.PLANNED, State.PRE_SENT, State.PINNED);

    private final int victimEntityId;
    private final TickClock clock;
    private final int journalCapacity;
    private final ArrayDeque<JournalEntry> journal = new ArrayDeque<>();

    /** The netty→owner hand-off slot: a pre-sent/pinned transaction ahead of its damage task. */
    private final AtomicReference<HitTransaction> wireSlot = new AtomicReference<>();

    private HitTransaction pending;
    private KnockbackVector pendingVector;
    private boolean vectorSubmitted;
    private boolean awaiting;

    public DeliveryDesk(int victimEntityId, TickClock clock, int journalCapacity) {
        this.victimEntityId = victimEntityId;
        this.clock = clock;
        this.journalCapacity = journalCapacity;
    }

    /** Netty entry: a PRE_SENT/PINNED transaction arriving ahead of its damage task. */
    public void submitFromWire(HitTransaction tx) {
        wireSlot.set(tx);
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
            record(pending, null, false, reason + " -> " + supersededBy.value());
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
        appendJournal(new JournalEntry(
                tx.context().id(), tx.context().source(), null, false, reason,
                clock.current(), tx.paceFactor()));
    }

    /**
     * The damage pass marks that the imminent velocity event resolves the pending
     * decision. It never resurrects a withdrawn or absent decision — the pending
     * transaction is established only by {@link #submit}/{@link #submitFromWire};
     * a resolve with nothing pending passes through (item 1).
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
            record(tx, null, false, "resistance-roll");
            directive = new Directive(Directive.Action.CANCEL_EVENT, null, null);
        } else if (tx.state() == State.PRE_SENT) {
            KnockbackVector carried = tx.carried();
            boolean unmodified = apiX == formula.x() && apiY == formula.y() && apiZ == formula.z();
            if (unmodified) {
                // 3. Unmodified pre-send: ship the wire vector and arm the valve.
                tx.adopted();
                record(tx, carried, true, null);
                directive = new Directive(Directive.Action.SHIP_AND_ARM_VALVE,
                        carried, ValvePayload.of(victimEntityId, carried));
            } else {
                // 4. A third party modified the event: ship the correction, no valve.
                KnockbackVector modified = new KnockbackVector(apiX, apiY, apiZ);
                tx.adopted();
                record(tx, modified, false, null);
                directive = new Directive(Directive.Action.SHIP, modified, null);
            }
        } else if (tx.state() == State.PINNED) {
            // 5. Pinned: ship normally, never a valve (B4).
            KnockbackVector carried = tx.carried();
            tx.adopted();
            record(tx, carried, false, null);
            directive = new Directive(Directive.Action.SHIP, carried, null);
        } else if (LIVE.contains(tx.state())) {
            // A live REGISTERED/PLANNED decision (vanilla path): ship the formula,
            // no valve. Reachable via adopted() from REGISTERED/PLANNED.
            tx.adopted();
            record(tx, formula, false, null);
            directive = new Directive(Directive.Action.SHIP, formula, null);
        } else {
            // F6 hardening: a RECORDED/terminal pending reached resolve. The Folia
            // counter/region skew can let the session sweep record+drop this tx just
            // before its deferred damage re-submits it; adopted() would throw INSIDE
            // the PlayerVelocityEvent handler ("Could not pass event
            // PlayerVelocityEvent") — vanilla's vector ships plus a doubled wire.
            // Journal a distinct note and pass the event through untouched; NEVER
            // transition a terminal tx.
            appendJournal(new JournalEntry(
                    tx.context().id(), tx.context().source(), null, false, "late-resolve-recorded",
                    clock.current(), tx.paceFactor()));
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
        drainWire();
        if (pending == null) {
            return;
        }
        TickStamp registeredAt = pending.context().registeredAt();
        if (!registeredAt.known() || !now.known() || registeredAt.value() >= now.value()) {
            return; // same tick (its velocity event may still come) or an unknown clock
        }
        if (LIVE.contains(pending.state())) {
            if (now.value() - registeredAt.value() >= 2) {
                pending.dropped("no-velocity-event");
                record(pending, null, false, "no-velocity-event");
                clearDecision();
            }
            return; // age 1: hold one more tick for the Folia skew window (F6)
        }
        // Already resolved but not recorded — no velocity event is coming, so
        // journal it now (tick-causal, never a valve).
        record(pending, null, false, reasonFor(pending.state()));
        clearDecision();
    }

    /** Ensure step for no-velocity-event sources: unresolved => Directive to setVelocity. */
    public Directive ensure(HitId id) {
        drainWire();
        if (pending != null && pending.context().id().equals(id) && LIVE.contains(pending.state())) {
            KnockbackVector vector = vectorSubmitted ? pendingVector : pending.carried();
            pending.ensured();
            record(pending, vector, false, null);
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
        HitTransaction fromWire = wireSlot.getAndSet(null);
        if (fromWire != null) {
            replacePending(fromWire, fromWire.carried());
        }
    }

    private void replacePending(HitTransaction tx, KnockbackVector vector) {
        if (pending != null && pending != tx && LIVE.contains(pending.state())) {
            // 6. Last-submitter-wins: the earlier decision is superseded + journaled.
            pending.suppressed("superseded");
            record(pending, null, false, "superseded");
        }
        pending = tx;
        pendingVector = vector;
        vectorSubmitted = true;
    }

    private void clearDecision() {
        pending = null;
        pendingVector = null;
        vectorSubmitted = false;
        awaiting = false;
    }

    /** Journals the transaction and marks it RECORDED (terminal). */
    private void record(HitTransaction tx, KnockbackVector shipped, boolean wireCarried, String reason) {
        appendJournal(new JournalEntry(
                tx.context().id(), tx.context().source(), shipped, wireCarried, reason,
                clock.current(), tx.paceFactor()));
        tx.recorded();
    }

    /** Appends a journal entry and evicts the oldest past capacity. */
    private void appendJournal(JournalEntry entry) {
        journal.addLast(entry);
        while (journal.size() > journalCapacity) {
            journal.removeFirst();
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
}
