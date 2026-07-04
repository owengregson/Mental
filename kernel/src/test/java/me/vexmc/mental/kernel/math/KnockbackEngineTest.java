package me.vexmc.mental.kernel.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.random.RandomGenerator;
import me.vexmc.mental.kernel.model.EntityState;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.profile.KnockbackDelivery;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.kernel.profile.ResistancePolicy;
import me.vexmc.mental.kernel.profile.VerticalMode;
import org.junit.jupiter.api.Test;

/**
 * Every expectation below is hand-computed from the 1.7.10 formula with the
 * default profile: base 0.4/0.4, extra 0.5/0.1 per level, vertical limit
 * 0.4 clamping the BASE before bonus levels (vanilla ordering), horizontal
 * limit off, friction 0.5 per axis, sprint worth one level. The 1.8.9
 * formula is byte-identical; only delivery differed. The newer fork knobs
 * (vertical-mode, range taper, wtap split, air multipliers, add offsets,
 * vertical floor) each get their own scenario and are no-ops at defaults.
 */
class KnockbackEngineTest {

    private static final double EPSILON = 1.0e-9;
    private static final KnockbackProfile DEFAULTS = KnockbackProfile.LEGACY_17;

    private static KnockbackProfile profile(double sprintFactor, ResistancePolicy resistance) {
        return withModifiers(DEFAULTS, sprintFactor, resistance);
    }

    private static KnockbackProfile withModifiers(
            KnockbackProfile base, double sprintFactor, ResistancePolicy resistance) {
        return new KnockbackProfile(
                base.name(), base.displayName(), base.description(),
                base.base(), base.verticalMode(), base.extra(), base.wtapExtra(),
                base.friction(), base.limits(), base.air(), base.add(), base.rangeReduction(),
                sprintFactor, base.combos(), base.meleeDelivery(), base.projectileDelivery(),
                resistance, base.shieldBlockingCancels());
    }

    private static EntityState attacker(double x, double z, float yaw, boolean sprinting, int enchant) {
        return new EntityState(x, 0, z, yaw, 0, 0, 0, true, sprinting, enchant, 0);
    }

    private static EntityState victim(double x, double z, double vx, double vy, double vz, double resistance) {
        return new EntityState(x, 0, z, 0.0f, vx, vy, vz, true, false, 0, resistance);
    }

    private static EntityState airborneVictim(double x, double z, double vx, double vy, double vz) {
        return new EntityState(x, 0, z, 0.0f, vx, vy, vz, false, false, 0, 0);
    }

    private static KnockbackVector computed(EntityState attacker, EntityState victim,
            KnockbackProfile profile, Double yOverride) {
        KnockbackVector vector = KnockbackEngine.compute(attacker, victim, profile, yOverride);
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
        Decay.Motion residual = Decay.decay(
                first.x(), first.y(), first.z(), 12, false, Decay.DEFAULT_GRAVITY);
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
                profile(1.0, ResistancePolicy.SCALING), null);

        assertEquals(0.12, vector.x(), EPSILON);
        assertEquals(0.4, vector.y(), EPSILON);
        assertEquals(0.16, vector.z(), EPSILON);
    }

    @Test
    void legacyResistanceIsAllOrNothing() {
        KnockbackProfile legacy = profile(1.0, ResistancePolicy.LEGACY);
        EntityState attacker = attacker(0, 0, 0.0f, false, 0);

        RandomGenerator alwaysResists = constantRandom(0.0); // roll 0.0 < resistance
        assertNull(KnockbackEngine.compute(
                attacker, victim(3, 4, 0, 0, 0, 0.5), legacy, null, alwaysResists, false));

        RandomGenerator neverResists = constantRandom(0.99);
        KnockbackVector full = KnockbackEngine.compute(
                attacker, victim(3, 4, 0, 0, 0, 0.5), legacy, null, neverResists, false);
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
        KnockbackProfile capped = new KnockbackProfile(
                DEFAULTS.name(), DEFAULTS.displayName(), DEFAULTS.description(),
                DEFAULTS.base(), DEFAULTS.verticalMode(), DEFAULTS.extra(), DEFAULTS.wtapExtra(),
                DEFAULTS.friction(), new KnockbackProfile.Limits(0.4, -3.9, 0.3),
                DEFAULTS.air(), DEFAULTS.add(), DEFAULTS.rangeReduction(),
                1.0, DEFAULTS.combos(), KnockbackDelivery.TRACKER, KnockbackDelivery.TRACKER, ResistancePolicy.NONE, DEFAULTS.shieldBlockingCancels());

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
                attacker(5, 5, 0.0f, false, 0), victim(5, 5, 0, 0, 0, 0), DEFAULTS, null, seeded, false);

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
                profile(2.0, ResistancePolicy.NONE), null);

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
    void computeBaseRandomOverloadMatchesRandomGeneratorOverload() {
        // Campaign D-8: the java.util.Random overload of computeBase exists so the
        // downgraded mega-jar never crosses a jvmdowngrader RandomGenerator stub type
        // between the tester and the kernel. It must be byte-identical to the
        // RandomGenerator path — same arithmetic, and where the coincident-position
        // branch draws from the source, the same random sequence.

        // 1. No draw (victim offset from source, zero resistance): the random source is
        //    never consulted, so the result is the pinned base math — hand-computed here
        //    from friction 0.5 and base push 0.4 (identical to the RandomGenerator-fed
        //    computeBasePushesAwayFromSourcePositionWithoutBonuses pin above).
        KnockbackVector viaRandom = KnockbackEngine.computeBase(
                victim(3, 4, 0.2, 0.0, 0.0, 0), 0.0, 0.0, DEFAULTS, null, new Random(42));
        assertNotNull(viaRandom);
        assertEquals(0.2 * 0.5 + 0.24, viaRandom.x(), EPSILON);
        assertEquals(0.4, viaRandom.y(), EPSILON);
        assertEquals(0.32, viaRandom.z(), EPSILON);

        // 2. Coincident source/victim: base() enters the tiny-random-direction branch and
        //    draws four nextDouble()s. Two Randoms seeded identically feed the two
        //    overloads, which must therefore agree bit-for-bit (exact equality, 0 delta) —
        //    proving the Random overload is a pure delegate, not a re-implementation.
        long seed = 0x9E3779B97F4A7C15L;
        KnockbackVector viaGenerator = KnockbackEngine.computeBase(
                victim(5, 5, 0, 0, 0, 0), 5.0, 5.0, DEFAULTS, null,
                (RandomGenerator) new Random(seed));
        KnockbackVector viaSeededRandom = KnockbackEngine.computeBase(
                victim(5, 5, 0, 0, 0, 0), 5.0, 5.0, DEFAULTS, null, new Random(seed));
        assertNotNull(viaGenerator);
        assertNotNull(viaSeededRandom);
        assertEquals(viaGenerator.x(), viaSeededRandom.x(), 0.0);
        assertEquals(viaGenerator.y(), viaSeededRandom.y(), 0.0);
        assertEquals(viaGenerator.z(), viaSeededRandom.z(), 0.0);
        // The tiny-direction knock still normalizes to the base magnitude.
        assertEquals(0.4, Math.hypot(viaSeededRandom.x(), viaSeededRandom.z()), EPSILON);
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

    /* ------------------------------------------------------------------ */
    /*  The fork knob vocabulary — each an era-exact no-op at defaults     */
    /* ------------------------------------------------------------------ */

    @Test
    void verticalModeSetAssignsRegardlessOfVictimMotionAndOverride() {
        KnockbackProfile assigned = new KnockbackProfile(
                "set", "Set", "", new KnockbackProfile.Push(0.4, 0.25), VerticalMode.SET,
                DEFAULTS.extra(), DEFAULTS.wtapExtra(), DEFAULTS.friction(),
                new KnockbackProfile.Limits(4.0, -3.9, -1.0), DEFAULTS.air(), DEFAULTS.add(),
                DEFAULTS.rangeReduction(), 1.0, false, KnockbackDelivery.TRACKER, KnockbackDelivery.TRACKER, ResistancePolicy.NONE, true);

        // Rising, falling, resting, and latency-hinted victims all launch identically.
        KnockbackVector rising = computed(
                attacker(0, 0, 0.0f, false, 0), victim(0, 4, 0, 0.9, 0, 0), assigned, null);
        KnockbackVector falling = computed(
                attacker(0, 0, 0.0f, false, 0), victim(0, 4, 0, -1.5, 0, 0), assigned, null);
        KnockbackVector hinted = computed(
                attacker(0, 0, 0.0f, false, 0), victim(0, 4, 0, 0.9, 0, 0), assigned, -0.5);

        assertEquals(0.25, rising.y(), EPSILON);
        assertEquals(0.25, falling.y(), EPSILON);
        assertEquals(0.25, hinted.y(), EPSILON);
        // Horizontal friction still applies; only the vertical is assigned.
        assertEquals(0.4, rising.z(), EPSILON);
    }

    @Test
    void rangeReductionTapersThePushBeyondItsStartDistance() {
        KnockbackProfile tapered = new KnockbackProfile(
                "taper", "Taper", "", DEFAULTS.base(), VerticalMode.ADD,
                DEFAULTS.extra(), DEFAULTS.wtapExtra(), DEFAULTS.friction(), DEFAULTS.limits(),
                DEFAULTS.air(), DEFAULTS.add(),
                new KnockbackProfile.RangeReduction(true, 3.0, 0.025, 1.2, 0.12),
                1.0, true, KnockbackDelivery.TRACKER, KnockbackDelivery.TRACKER, ResistancePolicy.NONE, true);

        // 2.5 blocks: inside the start distance — full push.
        KnockbackVector close = computed(
                attacker(0, 0, 0.0f, false, 0), victim(0, 2.5, 0, 0, 0, 0), tapered, null);
        assertEquals(0.4, close.z(), EPSILON);

        // 4 blocks: push − 0.025 × (4 − 1.2) = 0.4 − 0.07 = 0.33.
        KnockbackVector mid = computed(
                attacker(0, 0, 0.0f, false, 0), victim(0, 4, 0, 0, 0, 0), tapered, null);
        assertEquals(0.33, mid.z(), EPSILON);

        // 10 blocks: reduction capped at 0.12 → push 0.28.
        KnockbackVector far = computed(
                attacker(0, 0, 0.0f, false, 0), victim(0, 10, 0, 0, 0, 0), tapered, null);
        assertEquals(0.28, far.z(), EPSILON);

        // The distance is 3D: a victim 4 blocks away purely vertically tapers too.
        EntityState below = new EntityState(0, -4, 0.001, 0.0f, 0, 0, 0, true, false, 0, 0);
        KnockbackVector vertical = KnockbackEngine.compute(
                attacker(0, 0, 0.0f, false, 0), below, tapered, null,
                RandomGenerator.of("L64X128MixRandom"), false);
        assertNotNull(vertical);
        assertEquals(0.33, Math.hypot(vertical.x(), vertical.z()), 1.0e-6);

        // The base-only path (rods, projectiles) never tapers.
        KnockbackVector rod = KnockbackEngine.computeBase(
                victim(0, 10, 0, 0, 0, 0), 0.0, 0.0, tapered, null,
                RandomGenerator.of("L64X128MixRandom"));
        assertNotNull(rod);
        assertEquals(0.4, rod.z(), EPSILON);
    }

    @Test
    void wtapExtraReplacesTheSprintContributionOnFreshSprintOnly() {
        KnockbackProfile wtap = new KnockbackProfile(
                "wtap", "Wtap", "", DEFAULTS.base(), VerticalMode.ADD, DEFAULTS.extra(),
                new KnockbackProfile.WtapExtra(true, 0.8, 0.05),
                DEFAULTS.friction(), DEFAULTS.limits(), DEFAULTS.air(), DEFAULTS.add(),
                DEFAULTS.rangeReduction(), 1.0, true, KnockbackDelivery.TRACKER, KnockbackDelivery.TRACKER, ResistancePolicy.NONE, true);
        EntityState sprintingAttacker = attacker(0, 0, 0.0f, true, 1); // sprint + Knockback I
        EntityState restingVictim = victim(0, 4, 0, 0, 0, 0);
        RandomGenerator random = RandomGenerator.of("L64X128MixRandom");

        // Fresh sprint (w-tap): sprint level uses 0.8/0.05, enchant level stays 0.5.
        KnockbackVector fresh = KnockbackEngine.compute(
                sprintingAttacker, restingVictim, wtap, null, random, true);
        assertNotNull(fresh);
        assertEquals(0.4 + 1.0 * 0.8 + 1.0 * 0.5, fresh.z(), EPSILON);
        assertEquals(0.4 + 0.05, fresh.y(), EPSILON);

        // Continuous sprint: plain extra for everything.
        KnockbackVector continuous = KnockbackEngine.compute(
                sprintingAttacker, restingVictim, wtap, null, random, false);
        assertNotNull(continuous);
        assertEquals(0.4 + 2.0 * 0.5, continuous.z(), EPSILON);
        assertEquals(0.4 + 0.1, continuous.y(), EPSILON);

        // Disabled knob: freshness changes nothing — the era default.
        KnockbackVector legacyFresh = KnockbackEngine.compute(
                sprintingAttacker, restingVictim, DEFAULTS, null, random, true);
        KnockbackVector legacyStale = KnockbackEngine.compute(
                sprintingAttacker, restingVictim, DEFAULTS, null, random, false);
        assertNotNull(legacyFresh);
        assertNotNull(legacyStale);
        assertEquals(legacyStale.z(), legacyFresh.z(), EPSILON);
        assertEquals(legacyStale.y(), legacyFresh.y(), EPSILON);
    }

    @Test
    void airMultipliersScaleAirborneVictimsOnly() {
        KnockbackProfile aired = new KnockbackProfile(
                "air", "Air", "", DEFAULTS.base(), VerticalMode.ADD, DEFAULTS.extra(),
                DEFAULTS.wtapExtra(), DEFAULTS.friction(), DEFAULTS.limits(),
                new KnockbackProfile.Push(0.6, 0.8), DEFAULTS.add(), DEFAULTS.rangeReduction(),
                1.0, true, KnockbackDelivery.TRACKER, KnockbackDelivery.TRACKER, ResistancePolicy.NONE, true);

        KnockbackVector grounded = computed(
                attacker(0, 0, 0.0f, false, 0), victim(3, 4, 0, 0, 0, 0), aired, null);
        assertEquals(0.24, grounded.x(), EPSILON);
        assertEquals(0.4, grounded.y(), EPSILON);
        assertEquals(0.32, grounded.z(), EPSILON);

        KnockbackVector airborne = computed(
                attacker(0, 0, 0.0f, false, 0), airborneVictim(3, 4, 0, 0, 0), aired, null);
        assertEquals(0.24 * 0.6, airborne.x(), EPSILON);
        assertEquals(0.4 * 0.8, airborne.y(), EPSILON);
        assertEquals(0.32 * 0.6, airborne.z(), EPSILON);
    }

    /**
     * The {@code signature} preset's tweaks. A combo opener lands on a grounded
     * victim; the follow-ups land airborne. signature is velt's wipe shape with
     * {@code air.horizontal 0.92}, {@code air.vertical 0.98} (the airborne
     * pocket trims), and {@code base.vertical 0.365} (a touch above the 0.36
     * cap). So a sprinting combo hit on an AIRBORNE victim is the same hit on
     * the GROUNDED opener trimmed ×0.92 horizontally and ×0.98 vertically, the
     * opener still caps at 0.36, and the 0.365 base shows only on a descending
     * victim. This pins the "second hit drifts too far" fix.
     */
    @Test
    void signatureTrimsTheAirborneComboHitButNotTheGroundedOpener() {
        KnockbackProfile velt = wipeShape(0.36, 1.0, 1.0);
        KnockbackProfile signature = wipeShape(0.365, 0.92, 0.98);

        // The grounded opener (victim at rest): base.vertical 0.365 caps at
        // 0.36, so the opener matches velt — the air trims never touch hit 1.
        KnockbackVector opener = computed(
                attacker(0, 0, 0.0f, true, 0), victim(3, 4, 0, 0, 0, 0), signature, null);
        KnockbackVector veltOpener = computed(
                attacker(0, 0, 0.0f, true, 0), victim(3, 4, 0, 0, 0, 0), velt, null);
        assertEquals(0.195, opener.x(), EPSILON);   // 0.6 × 0.325 base, no sprint on x at yaw 0
        assertEquals(0.36, opener.y(), EPSILON);    // 0.365 capped to 0.36
        assertEquals(0.76, opener.z(), EPSILON);    // 0.8 × 0.325 + 0.5 sprint
        assertEquals(veltOpener.x(), opener.x(), EPSILON);
        assertEquals(veltOpener.y(), opener.y(), EPSILON);
        assertEquals(veltOpener.z(), opener.z(), EPSILON);

        // The airborne follow-up: horizontal ×0.92, vertical ×0.98.
        KnockbackVector followUp = computed(
                attacker(0, 0, 0.0f, true, 0), airborneVictim(3, 4, 0, 0, 0), signature, null);
        assertEquals(0.195 * 0.92, followUp.x(), EPSILON);
        assertEquals(0.36 * 0.98, followUp.y(), EPSILON);
        assertEquals(0.76 * 0.92, followUp.z(), EPSILON);

        // velt would NOT trim it — the over-travel signature corrects.
        KnockbackVector veltFollowUp = computed(
                attacker(0, 0, 0.0f, true, 0), airborneVictim(3, 4, 0, 0, 0), velt, null);
        assertEquals(opener.x(), veltFollowUp.x(), EPSILON);
        assertEquals(opener.y(), veltFollowUp.y(), EPSILON);
        assertEquals(opener.z(), veltFollowUp.z(), EPSILON);

        // base.vertical 0.365 only shows on a DESCENDING victim (below the cap):
        // signature gives exactly +0.005 lift over velt on a falling grounded hit.
        KnockbackVector descendingSig = computed(
                attacker(0, 0, 0.0f, true, 0), victim(3, 4, 0, -0.5, 0, 0), signature, null);
        KnockbackVector descendingVelt = computed(
                attacker(0, 0, 0.0f, true, 0), victim(3, 4, 0, -0.5, 0, 0), velt, null);
        assertEquals(0.315, descendingSig.y(), EPSILON);  // −0.5 × 0.1 + 0.365
        assertEquals(0.31, descendingVelt.y(), EPSILON);  // −0.5 × 0.1 + 0.36
        assertEquals(0.005, descendingSig.y() - descendingVelt.y(), EPSILON);
    }

    /** velt's wipe body parameterized by the tuned base/air verticals and the air-horizontal trim. */
    private static KnockbackProfile wipeShape(double baseVertical, double airHorizontal, double airVertical) {
        return new KnockbackProfile(
                "wipe-shape", "Wipe shape", "",
                new KnockbackProfile.Push(0.325, baseVertical), VerticalMode.ADD,
                new KnockbackProfile.Push(0.5, 0.0),
                new KnockbackProfile.WtapExtra(false, 0.5, 0.0),
                new KnockbackProfile.Friction(0.1, 0.1, 0.1),
                new KnockbackProfile.Limits(0.36, -3.9, -1.0),
                new KnockbackProfile.Push(airHorizontal, airVertical),
                DEFAULTS.add(), DEFAULTS.rangeReduction(),
                1.0, false, KnockbackDelivery.TRACKER, KnockbackDelivery.TRACKER,
                ResistancePolicy.NONE, true);
    }

    @Test
    void addOffsetsDistributeByAxisShareAndMatchSigns() {
        KnockbackProfile added = new KnockbackProfile(
                "add", "Add", "", DEFAULTS.base(), VerticalMode.ADD, DEFAULTS.extra(),
                DEFAULTS.wtapExtra(), DEFAULTS.friction(), DEFAULTS.limits(), DEFAULTS.air(),
                new KnockbackProfile.Push(0.1, 0.05), DEFAULTS.rangeReduction(),
                1.0, true, KnockbackDelivery.TRACKER, KnockbackDelivery.TRACKER, ResistancePolicy.NONE, true);

        // Base vector (0.24, 0.4, 0.32): shares 0.24/0.56 and 0.32/0.56.
        KnockbackVector vector = computed(
                attacker(0, 0, 0.0f, false, 0), victim(3, 4, 0, 0, 0, 0), added, null);
        assertEquals(0.24 + 0.1 * (0.24 / 0.56), vector.x(), EPSILON);
        assertEquals(0.4 + 0.05, vector.y(), EPSILON);
        assertEquals(0.32 + 0.1 * (0.32 / 0.56), vector.z(), EPSILON);

        // A single-axis knock keeps its line: the whole addition lands on z,
        // sign-matched, and the zero x axis stays exactly zero.
        KnockbackVector axial = computed(
                attacker(0, 0, 0.0f, false, 0), victim(0, 4, 0, 0, 0, 0), added, null);
        assertEquals(0.0, axial.x(), EPSILON);
        assertEquals(0.4 + 0.1, axial.z(), EPSILON);

        KnockbackVector negative = computed(
                attacker(0, 4, 0.0f, false, 0), victim(0, 0, 0, 0, 0, 0), added, null);
        assertEquals(-(0.4 + 0.1), negative.z(), EPSILON);
    }

    @Test
    void verticalMinFloorsTheFinalVertical() {
        KnockbackProfile floored = new KnockbackProfile(
                "floor", "Floor", "", DEFAULTS.base(), VerticalMode.ADD, DEFAULTS.extra(),
                DEFAULTS.wtapExtra(), DEFAULTS.friction(),
                new KnockbackProfile.Limits(0.4, -0.2, -1.0), DEFAULTS.air(), DEFAULTS.add(),
                DEFAULTS.rangeReduction(), 1.0, true, KnockbackDelivery.TRACKER, KnockbackDelivery.TRACKER, ResistancePolicy.NONE, true);

        // A hard downward residual: 0.5 × −2.0 + 0.4 = −0.6 → floored at −0.2.
        KnockbackVector vector = computed(
                attacker(0, 0, 0.0f, false, 0), victim(0, 4, 0, -2.0, 0, 0), floored, null);
        assertEquals(-0.2, vector.y(), EPSILON);

        // The default floor (−3.9) only restates the packet clamp.
        KnockbackVector legacy = computed(
                attacker(0, 0, 0.0f, false, 0), victim(0, 4, 0, -2.0, 0, 0), DEFAULTS, null);
        assertEquals(-0.6, legacy.y(), EPSILON);
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
