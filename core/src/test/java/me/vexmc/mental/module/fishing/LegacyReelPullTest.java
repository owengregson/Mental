package me.vexmc.mental.module.fishing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

/** The 1.7.10 handleHookRetraction pull: {@code Δ × 0.1} plus {@code √distance × 0.08} lift. */
class LegacyReelPullTest {

    @Test
    void pullDrawsTowardTheAnglerWithRootDistanceLift() {
        Location angler = new Location(null, 0.0, 5.0, 0.0);
        Location caught = new Location(null, 4.0, 1.0, 3.0);

        Vector pull = FishingKnockbackModule.legacyPull(angler, caught);

        double distance = Math.sqrt(16.0 + 16.0 + 9.0);
        assertEquals(-0.4, pull.getX(), 1.0e-9);
        assertEquals(0.4 + Math.sqrt(distance) * 0.08, pull.getY(), 1.0e-9);
        assertEquals(-0.3, pull.getZ(), 1.0e-9);
    }

    @Test
    void zeroDistancePullIsZero() {
        Location spot = new Location(null, 2.0, 3.0, 4.0);
        Vector pull = FishingKnockbackModule.legacyPull(spot, spot.clone());

        assertEquals(0.0, pull.getX());
        assertEquals(0.0, pull.getY());
        assertEquals(0.0, pull.getZ());
    }
}
