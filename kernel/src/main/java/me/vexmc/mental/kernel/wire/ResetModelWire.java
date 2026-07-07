package me.vexmc.mental.kernel.wire;

import java.util.concurrent.atomic.AtomicReference;
import me.vexmc.mental.kernel.model.ResetModel;
import me.vexmc.mental.kernel.model.TickStamp;
import me.vexmc.mental.kernel.port.TickClock;

/**
 * Arrival-order sprint-reset truth for ONE attacker (servo dynamic-chase spec,
 * 2026-07-07; D1). Tracks the tick of the attacker's last sprint (re-)engage — the
 * phase of their re-acceleration ramp — plus the raw sprint/block flags, so the
 * pocket servo can price the attacker's close from where they ACTUALLY are in their
 * w-tap / blockhit cycle instead of a flat rate.
 *
 * <p>Fed by the rim from the same arrival-order packets {@code SprintWire} reads
 * (ENTITY_ACTION sprint START/STOP) plus the sword-block signal. It holds the
 * {@link TickClock} and stamps each write itself (the {@code SprintWire} idiom), so
 * a caller needs no tick. State is one immutable snapshot swapped by CAS (the
 * codebase idiom, the {@code SprintWire} licensing rule): the read on the victim's
 * region thread and the writes on the attacker's netty thread stay coherent.
 * {@link ResetModel#UNKNOWN} until a first signal, so a packetless or silent
 * attacker leaves the servo on its measured-ring / attribute fallback.</p>
 *
 * <p>Every sprint START is a ramp reset point: a held sprint keeps its first START
 * (the phase grows and the ramp decays to steady speed), a w-tapper updates it each
 * re-press (the phase drops and the ramp deficit is re-priced). A sword block is a
 * reset point too — it drops sprint on the era client, and the blockhit re-engage
 * restarts the ramp; the {@code blocking} flag rides so the caller can defer a
 * blockhitter to the measured-ring (a legacy client crawls, a modern one keeps
 * sprint). All timing is {@link TickStamp} deltas; no wall clock.</p>
 */
public final class ResetModelWire {

    /** The whole reset state as one immutable value; {@code resetTick} is the last (re-)engage. */
    private record State(boolean sprinting, boolean blocking, TickStamp resetTick, boolean seen) {}

    private static final State INITIAL = new State(false, false, TickStamp.NO_TICK, false);

    private final TickClock clock;
    private final AtomicReference<State> state = new AtomicReference<>(INITIAL);

    public ResetModelWire(TickClock clock) {
        this.clock = clock;
    }

    /** A sprint (re-)engage — the ramp restarts at the current tick (the reset phase → 0). */
    public void onSprintStart() {
        TickStamp now = clock.current();
        state.updateAndGet(s -> new State(true, s.blocking(), now, true));
    }

    /** A sprint stop — the raw flag drops; the reset tick (the ramp phase) is unchanged. */
    public void onSprintStop() {
        state.updateAndGet(s -> new State(false, s.blocking(), s.resetTick(), true));
    }

    /**
     * A sword block engaged — raises {@code blocking} (so the caller defers a
     * blockhitter to the measured-ring) but PRESERVES the sprint ramp phase. A
     * modern client keeps full sprint through Mental's damage-only block, so the
     * block is not a genuine re-accel reset; a post-release continuation must read
     * its true (settled) sprint phase, not a fictitiously fresh one.
     */
    public void onBlockRaise() {
        state.updateAndGet(s -> new State(s.sprinting(), true, s.resetTick(), true));
    }

    /** The sword block released — drops {@code blocking}; a no-op when not blocking. */
    public void onBlockRelease() {
        state.updateAndGet(s -> s.blocking()
                ? new State(s.sprinting(), false, s.resetTick(), s.seen()) : s);
    }

    /**
     * The reset model as of {@code now} — {@link ResetModel#UNKNOWN} before any
     * (re-)engage signal (a lone STOP, or a never-written wire), so the servo keeps
     * its measured-ring / attribute chase there.
     */
    public ResetModel modelAt(TickStamp now) {
        State s = state.get();
        if (!s.seen() || !s.resetTick().known() || now == null || !now.known()) {
            return ResetModel.UNKNOWN;
        }
        int phase = (int) Math.max(0L, (long) now.value() - s.resetTick().value());
        return new ResetModel(phase, s.sprinting(), s.blocking(), true);
    }
}
