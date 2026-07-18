package me.vexmc.mental.v5;

import me.vexmc.mental.kernel.model.KnockbackVector;
import org.bukkit.util.Vector;

/**
 * The kernel↔Bukkit vector seam — the converter deleted from Phase 1 (the
 * kernel is Bukkit-free), restored here in core where Bukkit is available.
 */
public final class Vectors {

    private Vectors() {}

    public static Vector toBukkit(KnockbackVector vector) {
        return new Vector(vector.x(), vector.y(), vector.z());
    }

    public static KnockbackVector fromBukkit(Vector vector) {
        return new KnockbackVector(vector.getX(), vector.getY(), vector.getZ());
    }
}
