package me.vexmc.mental.kernel.delivery;

import me.vexmc.mental.kernel.model.KnockbackVector;

/**
 * The exact wire quantization of a velocity packet: motion × 8000 as shorts,
 * each axis clamped to ±3.9 (the legacy velocity-packet encoding). The valve is
 * keyed on this integer encoding with no epsilon (spec §3.5), so the
 * ENTITY_VELOCITY write that follows can be matched byte-for-byte.
 */
public record ValvePayload(int entityId, short qx, short qy, short qz) {

    public static ValvePayload of(int entityId, KnockbackVector v) {
        return new ValvePayload(entityId, q(v.x()), q(v.y()), q(v.z()));
    }

    private static short q(double axis) {
        double clamped = Math.max(-3.9, Math.min(3.9, axis));
        return (short) (clamped * 8000);
    }
}
