package me.vexmc.mental.kernel.delivery;

import java.util.EnumSet;
import me.vexmc.mental.kernel.model.HitContext;
import me.vexmc.mental.kernel.model.HitGeometry;
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
 *                        → PINNED              (value pinned — connectionless victim
 *                                               OR wire-failed burst; ships once via
 *                                               the velocity event, never a valve — B4)
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

    /** The journal note for a burst the wire refused (BurstSender UNSENDABLE) — the pin downgrade. */
    public static final String WIRE_FAILED = "wire-failed";

    private final HitContext context;
    private State state = State.REGISTERED;
    private KnockbackVector carried;
    private boolean wireCarried;
    private String suppressReason;
    private String deliveryNote;
    private double paceFactor = 1.0;
    private double comboFactor = 1.0;

    public HitTransaction(HitContext context) {
        this.context = context;
    }

    public HitContext context() {
        return context;
    }

    /**
     * The speed-conformal pace factor the engine applied when this hit's vector
     * was computed (journal attribution, D-6). Defaults to {@code 1.0} — pace off,
     * suppressed before compute, or a projectile — until a compute site records
     * the factor it actually used via {@link #paceFactor(double)}. The desk
     * journals this on every ship/suppress, so a weak knock is attributable.
     */
    public double paceFactor() {
        return paceFactor;
    }

    /** Records the pace factor the engine applied to this hit's fresh horizontal knock (D-6). */
    public void paceFactor(double factor) {
        this.paceFactor = factor;
    }

    /**
     * The pocket-servo factor the engine applied when this hit's vector was
     * computed (combo-hold §3.2 journal attribution, D-6). Defaults to {@code 1.0}
     * — servo off, not-this-attacker, no lever, suppressed before compute, or a
     * projectile — until a compute site records the factor it used via {@link
     * #comboFactor(double)}. The desk journals it on every ship/suppress, so a
     * non-era combo stamp is attributable in one journal read (a {@code 0.8}/{@code
     * 1.2} is a hard servo clamp). A plain {@code double} — no downgraded stub type
     * crosses the tester boundary (D-8).
     */
    public double comboFactor() {
        return comboFactor;
    }

    /** Records the pocket-servo factor the engine applied to this hit's fresh horizontal knock (D-6). */
    public void comboFactor(double factor) {
        this.comboFactor = factor;
    }

    /*
     * The F9 journal-attribution stamps: the pre-send disposition, the base()
     * geometry actually consumed, and the effective victim profile. Written by the
     * compute/registration site (netty plan() or the region EDBEE), copied into the
     * journal entry's capture by the desk — the same one-writer hand-off pattern as
     * paceFactor/comboFactor. Null until a compute stamped them.
     */
    private String presend;          // pre-send disposition (F9 namespace), null = region path
    private HitGeometry geometry;    // the base() geometry actually consumed, null until a compute stamped it
    private String profileName;      // the effective victim profile at compute time, null until stamped

    public String presend() { return presend; }
    public void presend(String disposition) { this.presend = disposition; }
    public HitGeometry geometry() { return geometry; }
    public void geometry(HitGeometry geometry) { this.geometry = geometry; }
    public String profileName() { return profileName; }
    public void profileName(String name) { this.profileName = name; }

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

    /**
     * The wire refused this hit's burst (a user-null race or a mid-ship throw —
     * BurstSender returned UNSENDABLE having written nothing): pin the era-moment
     * vector so it ships once via the genuine velocity event and no valve ever
     * arms to eat the victim's only authoritative ENTITY_VELOCITY. The note is
     * journaled by the desk on the pinned ship, so a wire failure is visible in
     * one journal read instead of masquerading as a healthy wire-carried SHIP.
     */
    public void pinnedWireFailed(KnockbackVector eraVector) {
        pinned(eraVector);
        this.deliveryNote = WIRE_FAILED;
    }

    /** The delivery note the desk journals on a pinned ship ({@link #WIRE_FAILED}), or null. */
    public String deliveryNote() {
        return deliveryNote;
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
