package me.vexmc.mental.kernel.delivery;

import java.util.EnumSet;
import me.vexmc.mental.kernel.model.HitContext;
import me.vexmc.mental.kernel.model.KnockbackVector;

/**
 * One hit's lifecycle state machine (spec §3.2). Transitions are methods that
 * assert the current state; an illegal one throws an
 * {@link IllegalStateException} naming both states. Every transaction reaches a
 * terminal {@link State#RECORDED} through an explicit causal path — there are
 * no expiry timers.
 *
 * <pre>
 *   REGISTERED → PLANNED → PRE_SENT            (the wire carried the burst)
 *                        → PINNED              (value pinned; ships once via the
 *                                               velocity event, never a valve — B4)
 *             → { ADOPTED | SUPPRESSED | RETRACTED | DROPPED | ENSURED }   (resolved)
 *             → RECORDED                        (terminal; journal entry written)
 * </pre>
 *
 * <p>The PINNED/PRE_SENT distinction is a type-level guarantee: only a PRE_SENT
 * vector is {@link #wireCarried()}, so a PINNED hit can never arm a valve
 * (B4's conflation is unrepresentable).</p>
 */
public final class HitTransaction {

    public enum State {
        REGISTERED, PLANNED, PRE_SENT, PINNED,
        ADOPTED, SUPPRESSED, RETRACTED, DROPPED, ENSURED, RECORDED
    }

    /** The states from which a resolution may still be recorded (journaled → terminal). */
    private static final EnumSet<State> RESOLVED =
            EnumSet.of(State.ADOPTED, State.SUPPRESSED, State.RETRACTED, State.DROPPED, State.ENSURED);

    /** The pre-resolution states — a hit still live and unrecorded. */
    private static final EnumSet<State> LIVE =
            EnumSet.of(State.REGISTERED, State.PLANNED, State.PRE_SENT, State.PINNED);

    private final HitContext context;
    private State state = State.REGISTERED;
    private KnockbackVector carried;
    private boolean wireCarried;
    private String suppressReason;

    public HitTransaction(HitContext context) {
        this.context = context;
    }

    public HitContext context() {
        return context;
    }

    public State state() {
        return state;
    }

    /** The reason a suppression carried, or null. */
    public String suppressReason() {
        return suppressReason;
    }

    /** Terminal once the journal entry has been written. */
    public boolean terminal() {
        return state == State.RECORDED;
    }

    /** The carried PRE_SENT/PINNED vector, or null when none was ever set. */
    public KnockbackVector carried() {
        return carried;
    }

    /** True only when a PRE_SENT vector rode the wire — never for PINNED. */
    public boolean wireCarried() {
        return wireCarried;
    }

    public void planned() {
        transition(EnumSet.of(State.REGISTERED), State.PLANNED);
    }

    public void preSent(KnockbackVector wireVector) {
        transition(EnumSet.of(State.PLANNED), State.PRE_SENT);
        this.carried = wireVector;
        this.wireCarried = true;
    }

    public void pinned(KnockbackVector eraVector) {
        transition(EnumSet.of(State.PLANNED), State.PINNED);
        this.carried = eraVector;
        this.wireCarried = false;
    }

    public void adopted() {
        transition(EnumSet.of(State.REGISTERED, State.PLANNED, State.PRE_SENT, State.PINNED), State.ADOPTED);
    }

    public void suppressed(String reason) {
        transition(LIVE, State.SUPPRESSED);
        this.suppressReason = reason;
    }

    public void retracted() {
        transition(LIVE, State.RETRACTED);
    }

    public void dropped(String reason) {
        transition(LIVE, State.DROPPED);
        this.suppressReason = reason;
    }

    public void ensured() {
        transition(LIVE, State.ENSURED);
    }

    public void recorded() {
        transition(RESOLVED, State.RECORDED);
    }

    private void transition(EnumSet<State> allowedFrom, State target) {
        if (!allowedFrom.contains(state)) {
            throw new IllegalStateException(
                    "cannot transition to " + target + " from " + state
                            + " (allowed from " + allowedFrom + ")");
        }
        state = target;
    }
}
