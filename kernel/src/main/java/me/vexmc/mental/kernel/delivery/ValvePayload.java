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

    /**
     * Explicit {@code toString} with each {@code short} component WIDENED to int in the
     * concatenation. The record's auto-generated {@code toString} concatenates the raw
     * {@code short}s, which JVMDowngrader 1.3.6 mis-lowers to a non-existent
     * {@code StringBuilder.append(short)} — a {@code NoSuchMethodError} on the very Java-8
     * legacy servers the downgraded base tree serves (caught by {@code verifyJdk8Api}, the
     * closed-world H1 gate). Widening to int makes javac emit an {@code int} concatenation
     * that downgrades to the valid {@code StringBuilder.append(int)}; the printed digits are
     * identical. Behavior-preserving and additive — the record's API is unchanged.
     */
    @Override
    public String toString() {
        return "ValvePayload[entityId=" + entityId
                + ", qx=" + (int) qx + ", qy=" + (int) qy + ", qz=" + (int) qz + "]";
    }
}
