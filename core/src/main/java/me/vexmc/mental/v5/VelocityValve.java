package me.vexmc.mental.v5;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import me.vexmc.mental.kernel.delivery.ValvePayload;

/**
 * The velocity valve — the PacketEvents-independent replacement for
 * VelocityDuplicateSuppressor (spec §3.5). One armed {@link ValvePayload} per
 * victim, keyed on the exact wire encoding (motion×8000 shorts, integer
 * equality, no epsilon). The region thread arms it in the resolution step; the
 * ENTITY_VELOCITY send that follows consumes it once. Kept PacketEvents-free so
 * it is unit-testable now; the PE listener that feeds {@link #consume} is
 * Phase 4.
 */
public final class VelocityValve {

    private final ConcurrentHashMap<UUID, AtomicReference<ValvePayload>> slots = new ConcurrentHashMap<>();

    /** Arm the valve for a victim (the region-thread resolution step). */
    public void arm(UUID victim, ValvePayload payload) {
        slots.computeIfAbsent(victim, id -> new AtomicReference<>()).set(payload);
    }

    /**
     * Consume-once: an exact match (entity id + the three quantized axes) clears
     * the slot and returns true; anything else returns false (a foreign send).
     */
    public boolean consume(UUID victim, int entityId, short qx, short qy, short qz) {
        AtomicReference<ValvePayload> slot = slots.get(victim);
        if (slot == null) {
            return false;
        }
        ValvePayload armed = slot.get();
        if (armed != null && armed.entityId() == entityId
                && armed.qx() == qx && armed.qy() == qy && armed.qz() == qz) {
            return slot.compareAndSet(armed, null);
        }
        return false;
    }

    /** Session-tick sweep: drop an unconsumed payload (tick-causal, bounds residual risk). */
    public void clearStale(UUID victim) {
        AtomicReference<ValvePayload> slot = slots.get(victim);
        if (slot != null) {
            slot.set(null);
        }
    }

    /** Quit/retire: drop the victim's slot entirely so it does not leak across sessions. */
    public void forget(UUID victim) {
        slots.remove(victim);
    }
}
