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
        } else {
            // A non-pre-sent, non-pinned decision (vanilla path): ship the formula,
            // no valve. Not one of the numbered items but reachable via adopted()
            // from REGISTERED/PLANNED; kept for completeness.
            tx.adopted();
            record(tx, formula, false, null);
            directive = new Directive(Directive.Action.SHIP, formula, null);
        }
        clearDecision();
        return directive;
    }

    /** Session-tick sweep: any awaiting transaction from an earlier tick is DROPPED. */
    public void sweep(TickStamp now) {
        drainWire();
        if (pending == null) {
            return;
        }
        TickStamp registeredAt = pending.context().registeredAt();
        if (registeredAt.known() && now.known() && registeredAt.value() < now.value()) {
            if (LIVE.contains(pending.state())) {
                pending.dropped("no-velocity-event");
                record(pending, null, false, "no-velocity-event");
            } else {
                // Already resolved (e.g. retracted by the retire path) but not
                // recorded — journal it now (tick-causal, never a valve).
                record(pending, null, false, reasonFor(pending.state()));
            }
            clearDecision();
        }
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
        journal.addLast(new JournalEntry(
                tx.context().id(), tx.context().source(), shipped, wireCarried, reason, clock.current()));
        while (journal.size() > journalCapacity) {
            journal.removeFirst();
        }
        tx.recorded();
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
