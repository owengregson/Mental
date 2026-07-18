package me.vexmc.mental.kernel.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.random.RandomGenerator;
import me.vexmc.mental.kernel.model.EntityState;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.profile.KnockbackDelivery;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.kernel.profile.ModernKnockback;
import me.vexmc.mental.kernel.profile.PaceScaling;
import me.vexmc.mental.kernel.profile.ResistancePolicy;
import me.vexmc.mental.kernel.profile.VerticalMode;
import me.vexmc.mental.kernel.profile.VerticalShape;
import org.junit.jupiter.api.Test;

/**
 * The modern (Paper 26.1.2) melee formula, hand-computed from the two-stage
 * vanilla knockback (base positional knock then yaw-directed sprint/enchant
 * extra), each application scaling its added strength by {@code (1 − r)} and
 * halving the surviving motion. The grounded closed form reproduces the
 * live-measured modern wire — standing {@code (0.4, 0.3608)}, sprint
 * {@code (0.7, 0.4)} — the two cross-validation points the port was verified
 * against. A resting grounded victim parks at the {@code −0.0784} gravity
 * equilibrium, exactly as the legacy formula's pins assume.
 */
class ModernKnockbackEngineTest {

    private static final double EPSILON = 1.0e-9;

    /** The byte-exact 26.1.2 knobs with the formula ON. */
    private static final ModernKnockback VANILLA =
            new ModernKnockback(true, 0.4, 0.5, 0.5, 0.5, 0.5, 0.4, true);
    /** Vanilla knobs but with the downward toggle off (airborne victims lift like grounded). */
    private static final ModernKnockback UPLIFT =
            new ModernKnockback(true, 0.4, 0.5, 0.5, 0.5, 0.5, 0.4, false);

    /** A modern-formula profile whose every SHARED knob is a no-op (air 1/1, add 0/0, floor −3.9). */
    private static KnockbackProfile modern(ModernKnockback modern) {
        return modern(modern, new KnockbackProfile.Push(1.0, 1.0),
                new KnockbackProfile.Limits(0.4, -3.9, -1.0), new KnockbackProfile.Push(0.0, 0.0));
    }

    private static KnockbackProfile modern(
            ModernKnockback modern, KnockbackProfile.Push air,
            KnockbackProfile.Limits limits, KnockbackProfile.Push add) {
        return new KnockbackProfile(
                "modern", "Modern", "",
                new KnockbackProfile.Push(0.4, 0.4), VerticalMode.ADD,
                new KnockbackProfile.Push(0.5, 0.1), new KnockbackProfile.WtapExtra(false, 0.5, 0.1),
                new KnockbackProfile.Friction(0.5, 0.5, 0.5), limits, air, add,
                KnockbackProfile.RangeReduction.DISABLED, 1.0, false,
                KnockbackDelivery.IMMEDIATE, KnockbackDelivery.TRACKER, ResistancePolicy.NONE, true,
                PaceScaling.OFF, modern);
    }

    /** Attacker at the origin, facing {@code yaw}. */
    private static EntityState attacker(float yaw, boolean sprinting, int enchant) {
        return new EntityState(0, 0, 0, yaw, 0, 0, 0, true, sprinting, enchant, 0);
    }

    private static EntityState groundedVictim(double x, double z, double vy, double resistance) {
        return new EntityState(x, 0, z, 0.0f, 0, vy, 0, true, false, 0, resistance);
    }

    private static EntityState airborneVictim(double x, double z, double vx, double vy, double vz) {
        return new EntityState(x, 0, z, 0.0f, vx, vy, vz, false, false, 0, 0);
    }

    private static KnockbackVector computed(
            EntityState attacker, EntityState victim, KnockbackProfile profile, Double yOverride) {
        KnockbackVector vector = KnockbackEngine.compute(attacker, victim, profile, yOverride);
        assertNotNull(vector);
        return vector;
    }

    @Test
    void plainStandingHitShipsTheMeasuredModernWire() {
        // attacker due south of a standing victim (down +z): base knock only.
        KnockbackVector vector = computed(
                attacker(0.0f, false, 0), groundedVictim(0, 4, -0.0784, 0), modern(VANILLA), null);

        assertEquals(0.0, vector.x(), EPSILON);
        assertEquals(0.3608, vector.y(), EPSILON); // min(0.4, −0.0392 + 0.4)
        assertEquals(0.4, vector.z(), EPSILON);
    }

    @Test
    void sprintHitAddsTheYawDirectedExtraAndCapsTheVertical() {
        KnockbackVector vector = computed(
                attacker(0.0f, true, 0), groundedVictim(0, 4, -0.0784, 0), modern(VANILLA), null);

        assertEquals(0.0, vector.x(), EPSILON);
        assertEquals(0.4, vector.y(), EPSILON);  // min(0.4, 0.3608·0.5 + 0.5) = 0.4
        assertEquals(0.7, vector.z(), EPSILON);  // 0.4·0.5 + 0.5
    }

    @Test
    void sprintPlusKnockbackTwoStacksThreeExtraLevels() {
        // sprint (0.5) + KB II (2 × 0.5) = 1.5 extra along yaw 0.
        KnockbackVector vector = computed(
                attacker(0.0f, true, 2), groundedVictim(0, 4, -0.0784, 0), modern(VANILLA), null);

        assertEquals(0.0, vector.x(), EPSILON);
        assertEquals(0.4, vector.y(), EPSILON);
        assertEquals(1.7, vector.z(), EPSILON);  // 0.4·0.5 + 1.5
    }

    @Test
    void sprintExtraAtYawNinetyPinsTheNegativeSineAxis() {
        // Yaw 90 faces −x: sin = 1, cos = 0, so the extra's −sin term owns the
        // whole X axis. Every yaw-0 pin has sin = 0 — without this one a flipped
        // stage-2 X sign would ship green (the positional pin only anchors
        // stage 1). Stage 1 still pushes +z off the attacker's position.
        KnockbackVector vector = computed(
                attacker(90.0f, true, 0), groundedVictim(0, 4, -0.0784, 0), modern(VANILLA), null);

        assertEquals(-0.5, vector.x(), EPSILON); // 0·0.5 − sin(90°)·0.5
        assertEquals(0.4, vector.y(), EPSILON);  // min(0.4, 0.3608·0.5 + 0.5)
        assertEquals(0.2, vector.z(), EPSILON);  // 0.4·0.5 + cos(90°)·0.5
    }

    @Test
    void fractionalResistanceScalesEveryAddedStrengthNotTheResidual() {
        KnockbackVector vector = computed(
                attacker(0.0f, false, 0), groundedVictim(0, 4, -0.0784, 0.5), modern(VANILLA), null);

        assertEquals(0.0, vector.x(), EPSILON);
        assertEquals(0.1608, vector.y(), EPSILON); // min(0.4, −0.0392 + 0.2)
        assertEquals(0.2, vector.z(), EPSILON);     // 0.4 · (1 − 0.5)
    }

    @Test
    void airborneVictimKeepsItsVerticalWhenTheDownwardToggleIsOn() {
        // The modern mid-air slam: a falling victim gets zero lift, only the sideways push.
        KnockbackVector vector = computed(
                attacker(0.0f, false, 0), airborneVictim(-2, 0, 0.2, -0.5, 0), modern(VANILLA), null);

        assertEquals(-0.3, vector.x(), EPSILON); // vx·0.5 − 0.4
        assertEquals(-0.5, vector.y(), EPSILON); // pass-through, no lift
        assertEquals(0.0, vector.z(), EPSILON);
    }

    @Test
    void airborneVictimLiftsWhenTheDownwardToggleIsOff() {
        KnockbackVector vector = computed(
                attacker(0.0f, false, 0), airborneVictim(-2, 0, 0.2, -0.5, 0), modern(UPLIFT), null);

        assertEquals(-0.3, vector.x(), EPSILON);
        assertEquals(0.15, vector.y(), EPSILON); // min(0.4, −0.25 + 0.4)
        assertEquals(0.0, vector.z(), EPSILON);
    }

    @Test
    void verticalCapZeroDisablesTheCeiling() {
        ModernKnockback noCap = new ModernKnockback(true, 0.4, 0.5, 0.5, 0.5, 0.5, 0.0, true);
        KnockbackVector vector = computed(
                attacker(0.0f, false, 0), groundedVictim(0, 4, 0.9, 0), modern(noCap), null);

        assertEquals(0.85, vector.y(), EPSILON); // 0.9·0.5 + 0.4, uncapped
        assertEquals(0.4, vector.z(), EPSILON);
    }

    @Test
    void airMultipliersScaleTheModernVectorForAirborneVictims() {
        KnockbackProfile aired = modern(UPLIFT, new KnockbackProfile.Push(0.6, 0.8),
                new KnockbackProfile.Limits(0.4, -3.9, -1.0), new KnockbackProfile.Push(0.0, 0.0));
        KnockbackVector vector = computed(
                attacker(0.0f, false, 0), airborneVictim(0, 4, 0, -0.0784, 0), aired, null);

        assertEquals(0.24, vector.z(), EPSILON);     // 0.4 × 0.6
        assertEquals(0.28864, vector.y(), EPSILON);  // 0.3608 × 0.8
    }

    @Test
    void verticalMinFloorsTheFinalModernVertical() {
        KnockbackProfile floored = modern(VANILLA, new KnockbackProfile.Push(1.0, 1.0),
                new KnockbackProfile.Limits(0.4, 0.2, -1.0), new KnockbackProfile.Push(0.0, 0.0));
        // Airborne, downward toggle on: y passes through at −0.5, then the floor clamps to 0.2.
        KnockbackVector vector = computed(
                attacker(0.0f, false, 0), airborneVictim(0, 4, 0, -0.5, 0), floored, null);

        assertEquals(0.2, vector.y(), EPSILON);
    }

    @Test
    void compensationOverrideReplacesTheInputVerticalOnly() {
        KnockbackVector rising = computed(
                attacker(0.0f, false, 0), groundedVictim(0, 4, 0.3, 0), modern(VANILLA), 0.0);
        assertEquals(0.4, rising.y(), EPSILON); // min(0.4, 0·0.5 + 0.4)

        KnockbackVector falling = computed(
                attacker(0.0f, false, 0), groundedVictim(0, 4, 0.3, 0), modern(VANILLA), -0.5);
        assertEquals(0.15, falling.y(), EPSILON); // min(0.4, −0.25 + 0.4)
        assertEquals(0.4, falling.z(), EPSILON);   // horizontal untouched by the override
    }

    @Test
    void coincidentPositionsJitterToTheBaseMagnitude() {
        RandomGenerator seeded = RandomGenerator.of("L64X128MixRandom");
        KnockbackVector vector = KnockbackEngine.compute(
                attacker(0.0f, false, 0), groundedVictim(0, 0, -0.0784, 0), modern(VANILLA), null,
                seeded, false);

        assertNotNull(vector);
        assertEquals(0.4, Math.hypot(vector.x(), vector.z()), EPSILON); // |h| == baseStrength
    }

    @Test
    void baseKnockPointsAwayFromTheAttackerPosition() {
        // attacker at the origin, victim at +x → the knock points +x.
        KnockbackVector vector = computed(
                attacker(0.0f, false, 0), groundedVictim(2, 0, -0.0784, 0), modern(VANILLA), null);

        assertTrue(vector.x() > 0, "knock must point +x, was " + vector.x());
        assertEquals(0.4, vector.x(), EPSILON);
        assertEquals(0.0, vector.z(), EPSILON);
    }

    /* ------------------------------- CT8c split-vertical shape ------------------------- */

    /**
     * A CT8c-split modern component with the given base strength, the vanilla
     * grounded/airborne factors 0.75/0.5, cap 0.4, and no sprint/enchant (so the
     * whole knock is stage 1 — the base positional knock at {@code strength =
     * baseStrength·(1 − r)}).
     */
    private static ModernKnockback ct8c(double baseStrength) {
        return new ModernKnockback(true, baseStrength, 0.5, 0.5, 0.5, 0.5, 0.4, true,
                VerticalShape.CT8C_SPLIT, 0.75, 0.5);
    }

    @Test
    void ct8cGroundedVerticalIsGroundedFactorTimesStrengthCapped() {
        // grounded: vy = min(cap, 0.75·strength), independent of the victim's own vy.
        KnockbackVector low = computed(
                attacker(0.0f, false, 0), groundedVictim(0, 4, -0.0784, 0), modern(ct8c(0.4)), null);
        assertEquals(0.30, low.y(), EPSILON); // min(0.4, 0.75·0.4)
        assertEquals(0.4, low.z(), EPSILON);

        KnockbackVector capped = computed(
                attacker(0.0f, false, 0), groundedVictim(0, 4, -0.0784, 0), modern(ct8c(0.9)), null);
        assertEquals(0.40, capped.y(), EPSILON); // min(0.4, 0.75·0.9 = 0.675) → cap
        assertEquals(0.9, capped.z(), EPSILON);
    }

    @Test
    void ct8cAirborneVerticalAddsAirborneFactorTimesStrengthToTheInputVyThenCaps() {
        // airborne: vy = min(cap, vyIn + 0.5·strength). strength 0.4 throughout.
        assertEquals(0.00, computed(attacker(0.0f, false, 0),
                airborneVictim(0, 4, 0, -0.20, 0), modern(ct8c(0.4)), null).y(), EPSILON); // −0.20 + 0.20
        assertEquals(0.40, computed(attacker(0.0f, false, 0),
                airborneVictim(0, 4, 0, 0.30, 0), modern(ct8c(0.4)), null).y(), EPSILON); // min(0.4, 0.50)
        assertEquals(-0.80, computed(attacker(0.0f, false, 0),
                airborneVictim(0, 4, 0, -1.00, 0), modern(ct8c(0.4)), null).y(), EPSILON); // −1.00 + 0.20
    }

    @Test
    void theEightArgConstructorDefaultsToTheVanillaShapeAndInertFactors() {
        // The delegating ctor fills VerticalShape.VANILLA, 0.75, 0.5 — the factors
        // are carried but inert under VANILLA, so a modern-vanilla component built
        // the short way equals the long VANILLA-shape spelling exactly.
        ModernKnockback viaShort = new ModernKnockback(true, 0.4, 0.5, 0.5, 0.5, 0.5, 0.4, true);
        assertEquals(VerticalShape.VANILLA, viaShort.verticalShape());
        assertEquals(0.75, viaShort.groundedVerticalFactor(), 0.0);
        assertEquals(0.5, viaShort.airborneVerticalFactor(), 0.0);
        assertEquals(
                new ModernKnockback(true, 0.4, 0.5, 0.5, 0.5, 0.5, 0.4, true,
                        VerticalShape.VANILLA, 0.75, 0.5),
                viaShort);
    }

    @Test
    void vanillaShapeIsBitIdenticalRegardlessOfTheInertCt8cFactors() {
        // The no-op-at-default proof for the shape knob: changing ONLY the (inert)
        // CT8c factors while the shape stays VANILLA cannot move a single vector.
        ModernKnockback stock = new ModernKnockback(true, 0.4, 0.5, 0.5, 0.5, 0.5, 0.4, true);
        ModernKnockback wildFactors = new ModernKnockback(true, 0.4, 0.5, 0.5, 0.5, 0.5, 0.4, true,
                VerticalShape.VANILLA, 9.9, -9.9);

        EntityState atk = new EntityState(0, 0, 0, 15.0f, 0, 0, 0, true, true, 1, 0);
        EntityState vic = new EntityState(0, 0, 4, 0.0f, 0.2, 0.3, -0.4, false, false, 0, 0);
        KnockbackVector a = computed(atk, vic, modern(stock), null);
        KnockbackVector b = computed(atk, vic, modern(wildFactors), null);
        assertEquals(a.x(), b.x(), 0.0);
        assertEquals(a.y(), b.y(), 0.0);
        assertEquals(a.z(), b.z(), 0.0);
    }

    @Test
    void switchingOnlyTheShapeToCt8cSplitChangesTheGroundedVertical() {
        // Proves the branch is wired AND that VANILLA is untouched: the same plain
        // grounded hit ships 0.3608 under VANILLA (vy·0.5 + strength, capped) and
        // 0.30 under CT8C_SPLIT (0.75·strength) — everything else identical.
        KnockbackVector vanilla = computed(
                attacker(0.0f, false, 0), groundedVictim(0, 4, -0.0784, 0), modern(VANILLA), null);
        KnockbackVector split = computed(
                attacker(0.0f, false, 0), groundedVictim(0, 4, -0.0784, 0), modern(ct8c(0.4)), null);
        assertEquals(0.3608, vanilla.y(), EPSILON);
        assertEquals(0.30, split.y(), EPSILON);
        assertEquals(vanilla.z(), split.z(), EPSILON); // horizontal unchanged by the shape
    }

    @Test
    void modernOffIsBitIdenticalToAProfileWithoutTheComponent() {
        // The era-exact no-op proof: adding the modern component with enabled=false
        // (via the 20-arg ctor) computes byte-identically to the legacy default
        // (built via the 18-arg ctor, which defaults modern to OFF).
        KnockbackProfile legacy = KnockbackProfile.LEGACY_17;
        KnockbackProfile withOffComponent = new KnockbackProfile(
                legacy.name(), legacy.displayName(), legacy.description(),
                legacy.base(), legacy.verticalMode(), legacy.extra(), legacy.wtapExtra(),
                legacy.friction(), legacy.limits(), legacy.air(), legacy.add(), legacy.rangeReduction(),
                legacy.sprintFactor(), legacy.combos(), legacy.meleeDelivery(), legacy.projectileDelivery(),
                legacy.resistance(), legacy.shieldBlockingCancels(), PaceScaling.OFF, ModernKnockback.OFF);
        assertEquals(legacy, withOffComponent);
        assertTrue(legacy.sameValues(withOffComponent));

        EntityState atk = new EntityState(0, 0, 0, 0.0f, 0, 0, 0, true, true, 1, 0);
        EntityState vic = new EntityState(0, 0, 4, 0.0f, 0.2, 0.3, -0.4, true, false, 0, 0);
        KnockbackVector viaLegacy = computed(atk, vic, legacy, null);
        KnockbackVector viaComponent = computed(atk, vic, withOffComponent, null);
        assertEquals(viaLegacy.x(), viaComponent.x(), 0.0);
        assertEquals(viaLegacy.y(), viaComponent.y(), 0.0);
        assertEquals(viaLegacy.z(), viaComponent.z(), 0.0);
    }
}
