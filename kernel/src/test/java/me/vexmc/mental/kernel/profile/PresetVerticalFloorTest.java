package me.vexmc.mental.kernel.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.random.RandomGenerator;
import me.vexmc.mental.kernel.math.KnockbackEngine;
import me.vexmc.mental.kernel.model.EntityState;
import me.vexmc.mental.kernel.model.KnockbackVector;
import org.junit.jupiter.api.Test;

/**
 * The 2.4.7 practice floor. In ADD mode the engine ships
 * {@code y = vy × friction.y + base.vertical (+ extra.vertical on a bonus hit)}
 * with the {@code limits.verticalMin} floor as the only negative gate — and the
 * −3.9 filler every preset carried was a no-op, so a deep falling ledger vy
 * shipped a DOWNWARD combo knock on every practice preset (thresholds
 * −0.576 … −0.90) while velt/signature were immune purely because friction.y
 * 0.1 puts their thresholds (−3.6) past the −3.92 decay terminal. The archived
 * configs carried NO vertical floor knob (−3.9 was Mental's schema filler), and
 * the real servers' true physics never reached the thresholds in flat play —
 * every measured era hit-2 vertical is positive (combat compendium §1.4/§3) —
 * so the five practice presets now floor the final vertical at 0.0 while
 * legacy-1.7/1.8 keep the unfloored era law (era vanilla DID knock long-falling
 * victims downward) and velt/signature stay byte-identical.
 *
 * <p>The staged vy values are the airborne ledger free-fall
 * ({@code vy ← (vy − 0.08) × 0.98}) at the measured combo cadence: −0.78113
 * (tick 10, the plain-hit pins) and −0.90543 (tick 12, the sprint pins).</p>
 */
class PresetVerticalFloorTest {

    private static final double EPSILON = 1.0e-9;

    /** Airborne ledger free-fall at combo-cadence tick 10 (plain pins). */
    private static final double FREE_FALL_T10 = -0.78113;
    /** Airborne ledger free-fall at combo-cadence tick 12 (sprint pins). */
    private static final double FREE_FALL_T12 = -0.90543;
    /** The airborne decay terminal ({@code Decay} clamps at −3.92). */
    private static final double TERMINAL = -3.92;

    private static EntityState attacker(boolean sprinting) {
        return new EntityState(0, 0, 0, 0.0f, 0, 0, 0, true, sprinting, 0, 0);
    }

    private static EntityState airborneVictim(double vy) {
        return new EntityState(0, 0, 4, 0.0f, 0, vy, 0, false, false, 0, 0);
    }

    private static EntityState groundedVictim(double vy) {
        return new EntityState(0, 0, 4, 0.0f, 0, vy, 0, true, false, 0, 0);
    }

    private static double shippedY(KnockbackProfile profile, boolean sprinting, EntityState victim) {
        KnockbackVector vector = KnockbackEngine.compute(attacker(sprinting), victim, profile, null);
        assertNotNull(vector);
        return vector.y();
    }

    @Test
    void sprintLeakClassHitsFloorAtZeroOnEveryPracticePreset() {
        // vy −0.90543. Pre-floor ADD verticals (hand-computed, the leak class):
        //   kohi    −0.90543 × 0.5    + 0.35 + 0.085 = −0.017715
        //   mmc     −0.90543 × 0.5556 + 0.32 + 0.1   = −0.083056908
        //   lunar   −0.90543 × 0.7634 + 0.44 + 0     = −0.251205262
        //   minehq  −0.90543 × 0.5    + 0.36 + 0.09  = −0.002715
        //   badlion −0.90543 × 0.5    + 0.34 + 0.085 = −0.027715
        // Every practice preset shipped DOWN; the 0.0 floor clamps them all.
        assertEquals(0.0, shippedY(Presets.KOHI, true, airborneVictim(FREE_FALL_T12)), EPSILON);
        assertEquals(0.0, shippedY(Presets.MMC, true, airborneVictim(FREE_FALL_T12)), EPSILON);
        assertEquals(0.0, shippedY(Presets.LUNAR, true, airborneVictim(FREE_FALL_T12)), EPSILON);
        assertEquals(0.0, shippedY(Presets.MINEHQ, true, airborneVictim(FREE_FALL_T12)), EPSILON);
        assertEquals(0.0, shippedY(Presets.BADLION, true, airborneVictim(FREE_FALL_T12)), EPSILON);
    }

    @Test
    void plainLeakClassHitsFloorAtZeroOnEveryPracticePreset() {
        // vy −0.78113. Pre-floor ADD verticals (hand-computed):
        //   kohi    −0.78113 × 0.5    + 0.35 = −0.040565
        //   mmc     −0.78113 × 0.5556 + 0.32 = −0.113995828
        //   lunar   −0.78113 × 0.7634 + 0.44 = −0.156314642
        //   minehq  −0.78113 × 0.5    + 0.36 = −0.030565
        //   badlion −0.78113 × 0.5    + 0.34 = −0.050565
        assertEquals(0.0, shippedY(Presets.KOHI, false, airborneVictim(FREE_FALL_T10)), EPSILON);
        assertEquals(0.0, shippedY(Presets.MMC, false, airborneVictim(FREE_FALL_T10)), EPSILON);
        assertEquals(0.0, shippedY(Presets.LUNAR, false, airborneVictim(FREE_FALL_T10)), EPSILON);
        assertEquals(0.0, shippedY(Presets.MINEHQ, false, airborneVictim(FREE_FALL_T10)), EPSILON);
        assertEquals(0.0, shippedY(Presets.BADLION, false, airborneVictim(FREE_FALL_T10)), EPSILON);
    }

    @Test
    void terminalVelocityHitsFloorAtZeroInsteadOfSmashingDown() {
        // The worst case (a missed landing run to terminal): kohi sprint shipped
        // −3.92 × 0.5 + 0.35 + 0.085 = −1.525 — a block-scale downward smash.
        assertEquals(0.0, shippedY(Presets.KOHI, true, airborneVictim(TERMINAL)), EPSILON);
    }

    @Test
    void theFloorIsInertForEveryEraNormalHit() {
        // A faithful airborne combo hit (jump-curve vy −0.37390 at tick 10):
        // kohi sprint = −0.37390 × 0.5 + 0.35 + 0.085 = +0.24805 — positive,
        // floor untouched, byte-identical to the pre-floor engine.
        assertEquals(0.24805, shippedY(Presets.KOHI, true, airborneVictim(-0.37390)), EPSILON);

        // The grounded sprint opener at the −0.0784 equilibrium:
        // −0.0784 × 0.5 + 0.35 + 0.085 = +0.3958 — unchanged.
        assertEquals(0.3958, shippedY(Presets.KOHI, true, groundedVictim(-0.0784)), EPSILON);
    }

    @Test
    void veltAndSignatureAreUntouchedIncludingTheTerminalDip() {
        // The immunity pattern that identified the bug, pinned as UNCHANGED
        // (the owner's "signature/velt feel must not change at all"):
        //   velt      sprint @ −0.90543: −0.090543 + 0.36  = +0.269457
        //   signature sprint @ −0.90543: (−0.090543 + 0.365) × 0.98 = +0.26896786
        assertEquals(0.269457, shippedY(Presets.VELT, true, airborneVictim(FREE_FALL_T12)), EPSILON);
        assertEquals(0.26896786, shippedY(Presets.SIGNATURE, true, airborneVictim(FREE_FALL_T12)), EPSILON);

        // Even the imperceptible terminal-velocity dips still ship (floor −3.9):
        //   velt:      −3.92 × 0.1 + 0.36  = −0.032
        //   signature: (−3.92 × 0.1 + 0.365) × 0.98 = −0.02646
        assertEquals(-0.032, shippedY(Presets.VELT, true, airborneVictim(TERMINAL)), EPSILON);
        assertEquals(-0.02646, shippedY(Presets.SIGNATURE, true, airborneVictim(TERMINAL)), EPSILON);
    }

    @Test
    void legacyPresetsKeepTheUnflooredEraLaw() {
        // Era vanilla DID knock a long-falling victim downward (motY < −0.8):
        // legacy-1.7 plain @ −2.0 ships 0.5 × −2.0 + 0.4 = −0.6, exactly the
        // decompiled law — the era presets are deliberately NOT floored.
        assertEquals(-0.6, shippedY(KnockbackProfile.LEGACY_17, false, airborneVictim(-2.0)), EPSILON);
        assertEquals(-0.6, shippedY(Presets.LEGACY_18, false, airborneVictim(-2.0)), EPSILON);
    }

    @Test
    void projectilePathFloorsTheSameLeakClass() {
        // computeBase (rod/projectile knocks) shares finish(), so the floor
        // covers it too: kohi on a victim falling at −0.90543 pre-floors to
        // −0.90543 × 0.5 + 0.35 = −0.102715 → 0.0; velt ships its unchanged
        // −0.090543 + 0.36 = +0.269457 (no extras exist on this path).
        KnockbackVector kohi = KnockbackEngine.computeBase(
                airborneVictim(FREE_FALL_T12), 0.0, 0.0, Presets.KOHI, null,
                RandomGenerator.of("L64X128MixRandom"));
        assertNotNull(kohi);
        assertEquals(0.0, kohi.y(), EPSILON);

        KnockbackVector velt = KnockbackEngine.computeBase(
                airborneVictim(FREE_FALL_T12), 0.0, 0.0, Presets.VELT, null,
                RandomGenerator.of("L64X128MixRandom"));
        assertNotNull(velt);
        assertEquals(0.269457, velt.y(), EPSILON);
    }
}
