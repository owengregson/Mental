package me.vexmc.mental.v5.feature.knockback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import me.vexmc.mental.kernel.model.EntityState;
import org.junit.jupiter.api.Test;

/**
 * The recompute half of the era-moment yaw freeze: a REGISTERED region-path hit
 * folds the registration stamp over its live capture (the extras must face the
 * click-flush aim, not the 1–2-tick-later mouse drift); a null stamp keeps the
 * live capture untouched (Vanilla-source mint, packetless attacker).
 */
class KnockbackUnitYawTest {

    @Test
    void nullStampKeepsTheLiveCapture() {
        EntityState captured = new EntityState(
                1.0, 64.0, -2.0, 12.5f, 0.1, 0.0, -0.1, true, true, 1, 0.0);
        assertSame(captured, KnockbackUnit.adoptRegistrationYaw(captured, null));
    }

    @Test
    void stampOverridesTheLiveYaw() {
        EntityState captured = new EntityState(
                1.0, 64.0, -2.0, 90.0f, 0.1, 0.0, -0.1, true, true, 1, 0.0);
        EntityState turned = KnockbackUnit.adoptRegistrationYaw(captured, 15.5f);

        assertEquals(15.5f, turned.yaw());
        assertEquals(1.0, turned.x());
        assertEquals(64.0, turned.y());
        assertEquals(-2.0, turned.z());
        assertEquals(0.1, turned.vx());
        assertEquals(0.0, turned.vy());
        assertEquals(-0.1, turned.vz());
        assertEquals(true, turned.grounded());
        assertEquals(true, turned.sprinting());
        assertEquals(1, turned.knockbackEnchantLevel());
        assertEquals(0.0, turned.knockbackResistance());
    }
}
