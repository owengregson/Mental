package me.vexmc.mental.module.knockback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.random.RandomGenerator;
import me.vexmc.mental.config.KnockbackSettings;
import me.vexmc.mental.config.ResistancePolicy;
import org.junit.jupiter.api.Test;

/**
 * Every expectation below is hand-computed from the 1.7.10 formula with the
 * default settings: base 0.4/0.4, extra 0.5/0.1 per level, vertical limit
 * 0.4 clamping the BASE before bonus levels (vanilla ordering), horizontal
 * limit off, friction 0.5 per axis, sprint worth one level. The 1.8.9
 * formula is byte-identical; only delivery differed.
 */
class KnockbackEngineTest {

    private static final double EPSILON = 1.0e-9;
    private static final KnockbackSettings DEFAULTS = settings(1.0, ResistancePolicy.NONE);

    private static KnockbackSettings settings(double sprintFactor, ResistancePolicy resistance) {
        return new KnockbackSettings(
                true, 0.4, 0.4, 0.5, 0.1, 0.4, -1.0, 0.5, 0.5, 0.5,
                sprintFactor, true, resistance, true);
    }

    private static EntityState attacker(double x, double z, float yaw, boolean sprinting, int enchant) {
        return new EntityState(x, z, yaw, 0, 0, 0, sprinting, enchant, 0);
    }

    private static EntityState victim(double x, double z, double vx, double vy, double vz, double resistance) {
        return new EntityState(x, z, 0.0f, vx, vy, vz, false, 0, resistance);
    }

    private static KnockbackVector computed(EntityState attacker, EntityState victim,
            KnockbackSettings settings, Double yOverride) {
        KnockbackVector vector = KnockbackEngine.compute(attacker, victim, settings, yOverride);
        assertNotNull(vector);
        return vector;
    }

    @Test
    void plainHitPushesAwayFromAttacker() {
        KnockbackVector vector = computed(
                attacker(0, 0, 0.0f, false, 0), victim(3, 4, 0, 0, 0, 0), DEFAULTS, null);

        assertEquals(0.24, vector.x(), EPSILON);
        assertEquals(0.4, vector.y(), EPSILON);
        assertEquals(0.32, vector.z(), EPSILON);
    }

    @Test
    void sprintHitAddsOneBonusLevelAndExceedsVerticalLimit() {
        KnockbackVector vector = computed(
                attacker(0, 0, 0.0f, true, 0), victim(3, 4, 0, 0, 0, 0), DEFAULTS, null);

        assertEquals(0.24, vector.x(), EPSILON);
        assertEquals(0.5, vector.y(), EPSILON); // base clamped to 0.4 THEN +0.1 bonus
        assertEquals(0.82, vector.z(), EPSILON);
    }

    @Test
    void frictionCarriesHalfTheVictimMotionAndBaseYIsClamped() {
        KnockbackVector vector = computed(
                attacker(0, 0, 0.0f, false, 0), victim(3, 4, 0.2, 0.3, -0.4, 0), DEFAULTS, null);

        assertEquals(0.34, vector.x(), EPSILON);
        assertEquals(0.4, vector.y(), EPSILON); // 0.15 + 0.4 = 0.55 -> clamped
        assertEquals(0.12, vector.z(), EPSILON);
    }

    @Test
    void comboHitCompoundsTheDecayedResidual() {
        // Hit 1: plain hit on a resting victim straight down the +z axis.
        KnockbackVector first = computed(
                attacker(0, 0, 0.0f, false, 0), victim(0, 4, 0, 0, 0, 0), DEFAULTS, null);
        assertEquals(0.4, first.z(), EPSILON);
        assertEquals(0.4, first.y(), EPSILON);

        // The residual decays 12 ticks airborne — exactly what the ledger feeds hit 2.
        VictimMotion.Motion residual = VictimMotion.decay(
                first.x(), first.y(), first.z(), 12, false, VictimMotion.DEFAULT_GRAVITY);
        KnockbackVector second = computed(
                attacker(0, 0, 0.0f, false, 0),
                victim(0, 4, residual.vx(), residual.vy(), residual.vz(), 0),
                DEFAULTS, null);

        // z = residual/2 + 0.4 > first hit's 0.4 — the 1.7.10 stack.
        assertEquals(residual.vz() * 0.5 + 0.4, second.z(), EPSILON);
        assertTrue(second.z() > first.z());
    }

    @Test
    void knockbackResistanceScalingPolicyScalesHorizontalOnly() {
        KnockbackVector vector = computed(
                attacker(0, 0, 0.0f, false, 0), victim(3, 4, 0, 0, 0, 0.5),
                settings(1.0, ResistancePolicy.SCALING), null);

        assertEquals(0.12, vector.x(), EPSILON);
        assertEquals(0.4, vector.y(), EPSILON);
        assertEquals(0.16, vector.z(), EPSILON);
    }

    @Test
    void legacyResistanceIsAllOrNothing() {
        KnockbackSettings legacy = settings(1.0, ResistancePolicy.LEGACY);
        EntityState attacker = attacker(0, 0, 0.0f, false, 0);

        RandomGenerator alwaysResists = constantRandom(0.0); // roll 0.0 < resistance
        assertNull(KnockbackEngine.compute(
                attacker, victim(3, 4, 0, 0, 0, 0.5), legacy, null, alwaysResists));

        RandomGenerator neverResists = constantRandom(0.99);
        KnockbackVector full = KnockbackEngine.compute(
                attacker, victim(3, 4, 0, 0, 0, 0.5), legacy, null, neverResists);
        assertNotNull(full);
        assertEquals(0.24, full.x(), EPSILON); // unscaled — the roll passed, full knockback
        assertEquals(0.32, full.z(), EPSILON);
    }

    @Test
    void enchantLevelsStackWithYawDirection() {
        KnockbackVector vector = computed(
                attacker(0, 0, 90.0f, false, 2), victim(3, 4, 0, 0, 0, 0), DEFAULTS, null);

        assertEquals(-0.76, vector.x(), EPSILON); // 0.24 + (-sin 90deg) * 2 * 0.5
        assertEquals(0.5, vector.y(), EPSILON);
        assertEquals(0.32, vector.z(), EPSILON);
    }

    @Test
    void horizontalLimitRescalesBothAxes() {
        KnockbackSettings capped = new KnockbackSettings(
                true, 0.4, 0.4, 0.5, 0.1, 0.4, 0.3, 0.5, 0.5, 0.5, 1.0,
                true, ResistancePolicy.NONE, true);

        KnockbackVector vector = computed(
                attacker(0, 0, 0.0f, false, 0), victim(3, 4, 0, 0, 0, 0), capped, null);

        assertEquals(0.18, vector.x(), EPSILON);
        assertEquals(0.24, vector.z(), EPSILON);
        assertEquals(0.3, Math.hypot(vector.x(), vector.z()), EPSILON);
    }

    @Test
    void compensationOverrideReplacesVictimVerticalMotion() {
        KnockbackVector grounded = computed(
                attacker(0, 0, 0.0f, false, 0), victim(3, 4, 0, 0.3, 0, 0), DEFAULTS, 0.0);
        assertEquals(0.4, grounded.y(), EPSILON);

        KnockbackVector falling = computed(
                attacker(0, 0, 0.0f, false, 0), victim(3, 4, 0, 0.3, 0, 0), DEFAULTS, -0.5);
        assertEquals(0.15, falling.y(), EPSILON); // -0.25 + 0.4
    }

    @Test
    void zeroDistanceHitStaysFiniteWithBaseMagnitude() {
        RandomGenerator seeded = RandomGenerator.of("L64X128MixRandom");
        KnockbackVector vector = KnockbackEngine.compute(
                attacker(5, 5, 0.0f, false, 0), victim(5, 5, 0, 0, 0, 0), DEFAULTS, null, seeded);

        assertNotNull(vector);
        assertFalse(Double.isNaN(vector.x()));
        assertFalse(Double.isNaN(vector.z()));
        assertEquals(0.4, Math.hypot(vector.x(), vector.z()), EPSILON);
        assertEquals(0.4, vector.y(), EPSILON);
    }

    @Test
    void sprintFactorScalesTheSprintContribution() {
        KnockbackVector vector = computed(
                attacker(0, 0, 0.0f, true, 0), victim(3, 4, 0, 0, 0, 0),
                settings(2.0, ResistancePolicy.NONE), null);

        assertEquals(0.32 + 2.0 * 0.5, vector.z(), EPSILON);
        assertTrue(vector.y() <= 0.4 + 0.1 + EPSILON);
    }

    @Test
    void computeBasePushesAwayFromSourcePositionWithoutBonuses() {
        // A rod bobber knock: bare base 0.4 from the angler's position,
        // even though no attacker state (sprint, enchant) exists at all.
        KnockbackVector vector = KnockbackEngine.computeBase(
                victim(3, 4, 0.2, 0.0, 0.0, 0), 0.0, 0.0, DEFAULTS, null,
                RandomGenerator.of("L64X128MixRandom"));

        assertNotNull(vector);
        assertEquals(0.2 * 0.5 + 0.24, vector.x(), EPSILON);
        assertEquals(0.4, vector.y(), EPSILON);
        assertEquals(0.32, vector.z(), EPSILON);
    }

    @Test
    void axesClampToTheLegacyPacketLimit() {
        // A pathological residual cannot exceed the ±3.9 short-packet range.
        KnockbackVector vector = computed(
                attacker(0, 0, 0.0f, false, 0), victim(0, 4, 16.0, 9.0, -16.0, 0), DEFAULTS, null);

        assertEquals(3.9, vector.x(), EPSILON);   // 8 + 0 → clamped
        assertEquals(0.4, vector.y(), EPSILON);   // capped by the vertical limit first
        assertEquals(-3.9, vector.z(), EPSILON);  // −8 + 0.4 → clamped

        KnockbackVector raw = KnockbackEngine.clamp(-12.0, 5.0, 1.0);
        assertEquals(-3.9, raw.x(), EPSILON);
        assertEquals(3.9, raw.y(), EPSILON);
        assertEquals(1.0, raw.z(), EPSILON);
    }

    private static RandomGenerator constantRandom(double value) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return 0L;
            }

            @Override
            public double nextDouble() {
                return value;
            }
        };
    }
}
