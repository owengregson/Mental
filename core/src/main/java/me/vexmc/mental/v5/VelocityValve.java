package me.vexmc.mental.v5;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import me.vexmc.mental.kernel.delivery.ValvePayload;
import me.vexmc.mental.kernel.model.TickStamp;

/**
 * The velocity valve — the PacketEvents-independent replacement for
 * VelocityDuplicateSuppressor (spec §3.5). One armed {@link ValvePayload} per
 * victim, keyed on the exact wire encoding (motion×8000 shorts, integer
 * equality, no epsilon). The arm is placed by the {@code DeskRouter} MONITOR
 * confirm (the normal path — only once the velocity event has survived every
 * listener priority) or by the blocked-redelivery task (aged); the
 * ENTITY_VELOCITY duplicate the tracker re-emits consumes it once. The
 * session-tick sweep drops an arm only past the two-tick legal-dup grace, so a
 * task-phase arm survives to its own end-of-tick tracker dup. Mental's own
 * bursts are silent-written and never traverse this valve, so every
 * ENTITY_VELOCITY the valve sees is the vanilla tracker's or a foreign plugin's.
 */
public final class VelocityValve {

    /** Legal-dup grace: end-of-arm-tick emit + event-loop lag + Folia ±1 skew (mirrors DeliveryDesk.sweep). */
    private static final int DUP_GRACE_TICKS = 2;

    private record Armed(ValvePayload payload, TickStamp at) {}

    private final ConcurrentHashMap<UUID, AtomicReference<Armed>> slots = new ConcurrentHashMap<>();

    /** Arm the valve for a victim, stamped with the arming tick (MONITOR confirm / blocked task). */
    public void arm(UUID victim, ValvePayload payload, TickStamp at) {
        slots.computeIfAbsent(victim, id -> new AtomicReference<>()).set(new Armed(payload, at));
    }

    /**
     * Consume-once: an exact match (entity id + the three quantized axes) clears
     * the slot and returns true; anything else returns false (a foreign send).
     */
    public boolean consume(UUID victim, int entityId, short qx, short qy, short qz) {
        AtomicReference<Armed> slot = slots.get(victim);
        if (slot == null) {
            return false;
        }
        Armed armed = slot.get();
        if (armed != null && armed.payload().entityId() == entityId
                && armed.payload().qx() == qx && armed.payload().qy() == qy && armed.payload().qz() == qz) {
            return slot.compareAndSet(armed, null);
        }
        return false;
    }

    /** Session-tick sweep: drop an arm only once it is provably past its legal duplicate (age >= 2 ticks or unknown clock). */
    public void clearStale(UUID victim, TickStamp now) {
        AtomicReference<Armed> slot = slots.get(victim);
        if (slot == null) return;
        Armed armed = slot.get();
        if (armed == null) return;
        if (!armed.at().known() || !now.known()
                || now.value() - armed.at().value() >= DUP_GRACE_TICKS) {
            slot.compareAndSet(armed, null);
        }
    }

    /** Quit/retire: drop the victim's slot entirely so it does not leak across sessions. */
    public void forget(UUID victim) {
        slots.remove(victim);
    }
}
