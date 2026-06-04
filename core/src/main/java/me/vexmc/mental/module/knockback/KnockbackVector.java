package me.vexmc.mental.module.knockback;

import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/** Immutable knockback result. */
public record KnockbackVector(double x, double y, double z) {

    public @NotNull Vector toBukkit() {
        return new Vector(x, y, z);
    }
}
