package me.vexmc.mental.kernel.model;

/**
 * A motion event emitted by the connection thread (GroundFsm) into the
 * victim's session inbox (spec §2, D1→D2). Immutable values only — the session
 * drains them and folds them into its {@code MotionLedger} on its own thread.
 */
public sealed interface LedgerEvent {

    /**
     * A grounded→airborne transition. {@code jumpVy} is the resolved vertical
     * stamp (the jump impulse plus any Jump Boost when rising, or the grounded
     * equilibrium for a non-jump step off a ledge); {@code pushX}/{@code pushZ}
     * are the sprint facing push, already resolved from the yaw.
     */
    record Liftoff(double jumpVy, double pushX, double pushZ, TickStamp tick) implements LedgerEvent {}

    /** An airborne→grounded transition. */
    record Landing(TickStamp tick) implements LedgerEvent {}

    /** The teleport ack seam: the FSM forgets its last state so no phantom jump crosses it. */
    record Reset(TickStamp tick) implements LedgerEvent {}
}
