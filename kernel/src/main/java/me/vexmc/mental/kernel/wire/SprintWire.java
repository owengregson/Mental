package me.vexmc.mental.kernel.wire;

import me.vexmc.mental.kernel.model.SprintVerdict;
import me.vexmc.mental.kernel.model.TickStamp;
import me.vexmc.mental.kernel.port.TickClock;

/**
 * Arrival-order sprint truth for ONE attacker, owned by their connection
 * thread (spec §2, D1; §4). The era server applied inbound packets in arrival
 * order, so an attack always read the sprint flag with every earlier
 * START/STOP already applied — a w-tap registered no matter how little time
 * separated the re-press from the click. This replays that in-order read with
 * no tick gating: {@link #verdictAt} returns the current wire state directly.
 *
 * <p>Freshness (WindSpigot's {@code isExtraKnockback}): a START arms it (the
 * re-engage IS the w-tap signal); the release half of a tap never disarms it;
 * a hit spends it via {@link #onServerClear}. Server-initiated
 * {@code setSprinting} drift never crosses the wire, so {@link #reconcile}
 * adopts the live flag only after the wire has been quiet — a fresh wire write
 * always wins the within-tick window it exists for. All timing is
 * {@link TickStamp} deltas; no wall clock.</p>
 */
public final class SprintWire {

    private final TickClock clock;

    private boolean seen;
    private boolean sprinting;
    private boolean armed;
    private TickStamp lastWrite = TickStamp.NO_TICK;

    public SprintWire(TickClock clock) {
        this.clock = clock;
    }

    /** A wire START: sets the flag and arms freshness (the re-engage is the w-tap). */
    public void onSprintStart() {
        seen = true;
        sprinting = true;
        armed = true;
        lastWrite = clock.current();
    }

    /** A wire STOP: drops the flag only — armed freshness survives the release half. */
    public void onSprintStop() {
        seen = true;
        sprinting = false;
        lastWrite = clock.current();
    }

    /**
     * Mirror of vanilla's in-attack sprint clear, applied when the desk reports
     * an accepted bonus hit: the flag drops and the freshness the hit used is
     * spent. A later wire START re-arms both.
     */
    public void onServerClear() {
        seen = true;
        sprinting = false;
        armed = false;
        lastWrite = clock.current();
    }

    /**
     * Re-seed from the published server flag after the wire has been quiet for
     * {@code quietTicks}, or when the wire has never been written (absent). A
     * fresh wire write inside the window is never overwritten by a stale-high
     * server flag.
     */
    public void reconcile(boolean serverSprinting, TickStamp now, int quietTicks) {
        if (!seen) {
            seen = true;
            sprinting = serverSprinting;
            armed = false;
            lastWrite = now;
            return;
        }
        if (sprinting != serverSprinting && lastWrite.known() && now.known()
                && now.value() - lastWrite.value() >= quietTicks) {
            sprinting = serverSprinting;
            lastWrite = now;
        }
    }

    /** The registration-time verdict (never null; falls back to the seeded state). */
    public SprintVerdict verdictAt(TickStamp now) {
        return new SprintVerdict(sprinting, armed, now);
    }
}
