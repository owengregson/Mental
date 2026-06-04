package me.vexmc.mental.module.knockback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.random.RandomGenerator;
import me.vexmc.mental.config.KnockbackSettings;
import org.junit.jupiter.api.Test;

/**
 * Every expectation below is hand-computed from the 1.8 formula with the
 * default settings: base 0.4/0.4, extra 0.5/0.1 per level, vertical limit
 * 0.4 clamping the BASE before bonus levels (vanilla ordering), horizontal
 * limit off, friction 0.5 per axis, sprint worth one level.
 */
class KnockbackEngineTest {

    private static final double EPSILON = 1.0e-9;
    private static final KnockbackSettings DEFAULTS = new KnockbackSettings(
            true, 0.4, 0.4, 0.5, 0.1, 0.4, -1.0, 0.5, 0.5, 0.5, 1.0, false, true);

    private static EntityState attacker(double x, double z, float yaw, boolean sprinting, int enchant) {
        return new EntityState(x, z, yaw, 0, 0, 0, sprinting, enchant, 0);
    }

    private static EntityState victim(double x, double z, double vx, double vy, double vz, double resistance) {
        return new EntityState(x, z, 0.0f, vx, vy, vz, false, 0, resistance);
    }

    @Test
    void plainHitPushesAwayFromAttacker() {
        KnockbackVector vector = KnockbackEngine.compute(
                attacker(0, 0, 0.0f, false, 0), victim(3, 4, 0, 0, 0, 0), DEFAULTS, null);

        assertEquals(0.24, vector.x(), EPSILON);
        assertEquals(0.4, vector.y(), EPSILON);
        assertEquals(0.32, vector.z(), EPSILON);
    }

    @Test
    void sprintHitAddsOneBonusLevelAndExceedsVerticalLimit() {
        KnockbackVector vector = KnockbackEngine.compute(
                attacker(0, 0, 0.0f, true, 0), victim(3, 4, 0, 0, 0, 0), DEFAULTS, null);

        assertEquals(0.24, vector.x(), EPSILON);
        assertEquals(0.5, vector.y(), EPSILON); // base clamped to 0.4 THEN +0.1 bonus
        assertEquals(0.82, vector.z(), EPSILON);
    }

    @Test
    void frictionCarriesHalfTheVictimMotionAndBaseYIsClamped() {
        KnockbackVector vector = KnockbackEngine.compute(
                attacker(0, 0, 0.0f, false, 0), victim(3, 4, 0.2, 0.3, -0.4, 0), DEFAULTS, null);

        assertEquals(0.34, vector.x(), EPSILON);
        assertEquals(0.4, vector.y(), EPSILON); // 0.15 + 0.4 = 0.55 -> clamped
        assertEquals(0.12, vector.z(), EPSILON);
    }

    @Test
    void knockbackResistanceScalesHorizontalOnly() {
        KnockbackSettings honoring = new KnockbackSettings(
                true, 0.4, 0.4, 0.5, 0.1, 0.4, -1.0, 0.5, 0.5, 0.5, 1.0, true, true);

        KnockbackVector vector = KnockbackEngine.compute(
                attacker(0, 0, 0.0f, false, 0), victim(3, 4, 0, 0, 0, 0.5), honoring, null);

        assertEquals(0.12, vector.x(), EPSILON);
        assertEquals(0.4, vector.y(), EPSILON);
        assertEquals(0.16, vector.z(), EPSILON);
    }

    @Test
    void enchantLevelsStackWithYawDirection() {
        KnockbackVector vector = KnockbackEngine.compute(
                attacker(0, 0, 90.0f, false, 2), victim(3, 4, 0, 0, 0, 0), DEFAULTS, null);

        assertEquals(-0.76, vector.x(), EPSILON); // 0.24 + (-sin 90deg) * 2 * 0.5
        assertEquals(0.5, vector.y(), EPSILON);
        assertEquals(0.32, vector.z(), EPSILON);
    }

    @Test
    void horizontalLimitRescalesBothAxes() {
        KnockbackSettings capped = new KnockbackSettings(
                true, 0.4, 0.4, 0.5, 0.1, 0.4, 0.3, 0.5, 0.5, 0.5, 1.0, false, true);

        KnockbackVector vector = KnockbackEngine.compute(
                attacker(0, 0, 0.0f, false, 0), victim(3, 4, 0, 0, 0, 0), capped, null);

        assertEquals(0.18, vector.x(), EPSILON);
        assertEquals(0.24, vector.z(), EPSILON);
        assertEquals(0.3, Math.hypot(vector.x(), vector.z()), EPSILON);
    }

    @Test
    void compensationOverrideReplacesVictimVerticalMotion() {
        KnockbackVector grounded = KnockbackEngine.compute(
                attacker(0, 0, 0.0f, false, 0), victim(3, 4, 0, 0.3, 0, 0), DEFAULTS, 0.0);
        assertEquals(0.4, grounded.y(), EPSILON);

        KnockbackVector falling = KnockbackEngine.compute(
                attacker(0, 0, 0.0f, false, 0), victim(3, 4, 0, 0.3, 0, 0), DEFAULTS, -0.5);
        assertEquals(0.15, falling.y(), EPSILON); // -0.25 + 0.4
    }

    @Test
    void zeroDistanceHitStaysFiniteWithBaseMagnitude() {
        RandomGenerator seeded = RandomGenerator.of("L64X128MixRandom");
        KnockbackVector vector = KnockbackEngine.compute(
                attacker(5, 5, 0.0f, false, 0), victim(5, 5, 0, 0, 0, 0), DEFAULTS, null, seeded);

        assertFalse(Double.isNaN(vector.x()));
        assertFalse(Double.isNaN(vector.z()));
        assertEquals(0.4, Math.hypot(vector.x(), vector.z()), EPSILON);
        assertEquals(0.4, vector.y(), EPSILON);
    }

    @Test
    void sprintFactorScalesTheSprintContribution() {
        KnockbackSettings doubledSprint = new KnockbackSettings(
                true, 0.4, 0.4, 0.5, 0.1, 0.4, -1.0, 0.5, 0.5, 0.5, 2.0, false, true);

        KnockbackVector vector = KnockbackEngine.compute(
                attacker(0, 0, 0.0f, true, 0), victim(3, 4, 0, 0, 0, 0), doubledSprint, null);

        assertEquals(0.32 + 2.0 * 0.5, vector.z(), EPSILON);
        assertTrue(vector.y() <= 0.4 + 0.1 + EPSILON);
    }
}
