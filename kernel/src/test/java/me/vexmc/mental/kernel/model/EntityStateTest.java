package me.vexmc.mental.kernel.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The additive {@link EntityState#withYaw(float)} — the recompute half of the
 * era-moment yaw freeze folds the registration stamp over a live capture,
 * replacing only the yaw and preserving every other field.
 */
class EntityStateTest {

    @Test
    void withYawReplacesOnlyTheYaw() {
        EntityState original = new EntityState(
                1.5, 64.0, -2.25, 90.0f, 0.1, -0.2, 0.3, true, true, 2, 0.25, 0.13);
        EntityState turned = original.withYaw(15.5f);

        assertEquals(15.5f, turned.yaw());
        assertEquals(1.5, turned.x());
        assertEquals(64.0, turned.y());
        assertEquals(-2.25, turned.z());
        assertEquals(0.1, turned.vx());
        assertEquals(-0.2, turned.vy());
        assertEquals(0.3, turned.vz());
        assertEquals(true, turned.grounded());
        assertEquals(true, turned.sprinting());
        assertEquals(2, turned.knockbackEnchantLevel());
        assertEquals(0.25, turned.knockbackResistance());
        assertEquals(0.13, turned.moveSpeedAttr());
    }

    @Test
    void withYawOnTheElevenArgArityKeepsTheSentinel() {
        // The 11-arg (pre-pace-scaling) construction leaves the movement-speed
        // sentinel in place; withYaw must carry it through untouched.
        EntityState eleven = new EntityState(0, 0, 0, 0.0f, 0, 0, 0, false, false, 0, 0);
        assertEquals(EntityState.MOVE_SPEED_UNAVAILABLE, eleven.withYaw(1f).moveSpeedAttr());
    }
}
