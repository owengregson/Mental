package me.vexmc.mental.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;

import me.vexmc.mental.kernel.model.KnockbackVector;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

/** Pins the kernel↔Bukkit vector seam (the deleted Phase 1 converter), round-trip. */
class VectorsTest {

    private static final double EPSILON = 1.0e-12;

    @Test
    void toBukkitCarriesEachAxis() {
        Vector bukkit = Vectors.toBukkit(new KnockbackVector(0.9, 0.4608, -0.3));
        assertEquals(0.9, bukkit.getX(), EPSILON);
        assertEquals(0.4608, bukkit.getY(), EPSILON);
        assertEquals(-0.3, bukkit.getZ(), EPSILON);
    }

    @Test
    void fromBukkitCarriesEachAxis() {
        KnockbackVector kernel = Vectors.fromBukkit(new Vector(1.5, -2.0, 0.25));
        assertEquals(1.5, kernel.x(), EPSILON);
        assertEquals(-2.0, kernel.y(), EPSILON);
        assertEquals(0.25, kernel.z(), EPSILON);
    }

    @Test
    void roundTripsThroughBothDirections() {
        KnockbackVector original = new KnockbackVector(0.123, -0.456, 0.789);
        assertEquals(original, Vectors.fromBukkit(Vectors.toBukkit(original)));
    }
}
