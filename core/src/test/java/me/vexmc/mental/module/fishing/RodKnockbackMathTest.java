package me.vexmc.mental.module.fishing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.random.RandomGenerator;
import me.vexmc.mental.module.knockback.KnockbackVector;
import org.junit.jupiter.api.Test;

/**
 * Expectations hand-computed from the 1.8 rod formula (OCM heritage):
 * carry half the victim's motion, push 0.4 along the hook→victim line,
 * lift by 0.4 capped at 0.4.
 */
class RodKnockbackMathTest {

    private static final double EPSILON = 1.0e-9;

    @Test
    void restingVictimIsPushedAwayFromTheHook() {
        KnockbackVector vector = RodKnockbackMath.knockback(
                0, 0, 0, /* hook */ 2, 64, 0, /* victim */ 0, 0);

        assertEquals(-0.4, vector.x(), EPSILON);
        assertEquals(0.4, vector.y(), EPSILON);
        assertEquals(0.0, vector.z(), EPSILON);
    }

    @Test
    void halfTheVictimMotionCarriesThrough() {
        KnockbackVector vector = RodKnockbackMath.knockback(
                0.6, -0.4, -0.4, /* hook */ 0, 64, 3, /* victim */ 0, 0);

        assertEquals(0.3, vector.x(), EPSILON);
        assertEquals(0.2, vector.y(), EPSILON); // -0.2 + 0.4, under the cap
        assertEquals(-0.2 - 0.4, vector.z(), EPSILON);
    }

    @Test
    void upwardMotionIsCappedAtPointFour() {
        KnockbackVector vector = RodKnockbackMath.knockback(
                0, 1.0, 0, /* hook */ 2, 64, 0, /* victim */ 0, 0);

        assertEquals(0.4, vector.y(), EPSILON); // 0.5 + 0.4 = 0.9 -> capped
    }

    @Test
    void coincidentHookStaysFinite() {
        RandomGenerator seeded = RandomGenerator.of("L64X128MixRandom");
        KnockbackVector vector = RodKnockbackMath.knockback(
                0, 0, 0, 5, 64, 5, 5, 5, seeded);

        assertFalse(Double.isNaN(vector.x()));
        assertFalse(Double.isNaN(vector.z()));
        assertEquals(0.4, Math.hypot(vector.x(), vector.z()), EPSILON);
        assertEquals(0.4, vector.y(), EPSILON);
    }
}
