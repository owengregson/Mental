package me.vexmc.mental.kernel.math;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins the 1.8 armour defence pipeline (armour points → resistance → enchant
 * EPF → absorption) from the decompiled 1.8.9 sources cited in
 * {@code docs/superpowers/plans/2026-06-14-ocm-ground-truth.md} §4.
 *
 * <p>Every constant and stage here is a unit pin: drift breaks the build. The
 * EPF stage is the deterministic variant (no random multiplier) — see the
 * class doc on {@link DefenceMath#epf} for why the randomness is invisible.</p>
 */
class DefenceMathTest {

    private static final double EPS = 1.0e-9;

    /* ------------------------------------------------------------------ */
    /*  Armour points stage — dmg * (25 - points) / 25                     */
    /* ------------------------------------------------------------------ */

    @Test
    void fullDiamondArmourReducesTenToTwo() {
        // 20 armour points, no enchant, no resistance:
        //   10 * (25 - 20) / 25 = 10 * 5/25 = 2.0  [pr.java:709-712]
        assertEquals(2.0, DefenceMath.armourReduced(10.0, 20), EPS);
    }

    @Test
    void noArmourLeavesDamageUntouched() {
        assertEquals(10.0, DefenceMath.armourReduced(10.0, 0), EPS);
    }

    @Test
    void eachArmourPointIsFourPercent() {
        // 1 point → 10 * (25 - 1)/25 = 9.6 (4% reduction)
        assertEquals(9.6, DefenceMath.armourReduced(10.0, 1), EPS);
    }

    @Test
    void armourPointsClampAtTwentyFive() {
        // The divider is 25, so 25 points = full immunity; anything above is
        // clamped to 25 (vanilla armour tops out at 20 anyway).
        assertEquals(0.0, DefenceMath.armourReduced(10.0, 25), EPS);
        assertEquals(0.0, DefenceMath.armourReduced(10.0, 40), EPS);
    }

    /* ------------------------------------------------------------------ */
    /*  Resistance stage — 0.2 per level, cap at level 5 (100%)            */
    /* ------------------------------------------------------------------ */

    @Test
    void resistanceLevelOneTakesTwentyPercentOff() {
        assertEquals(8.0, DefenceMath.resistanceReduced(10.0, 1), EPS);
    }

    @Test
    void resistanceLevelZeroIsNoop() {
        assertEquals(10.0, DefenceMath.resistanceReduced(10.0, 0), EPS);
    }

    @Test
    void resistanceCapsAtFullImmunityLevelFive() {
        assertEquals(0.0, DefenceMath.resistanceReduced(10.0, 5), EPS);
        // Higher than 5 must not produce negative damage (clamp ≥ 0).
        assertEquals(0.0, DefenceMath.resistanceReduced(10.0, 6), EPS);
    }

    /* ------------------------------------------------------------------ */
    /*  Enchant EPF stage — floor((6+level²)/3 * typeModifier), summed     */
    /* ------------------------------------------------------------------ */

    @Test
    void protectionFourSinglePieceEpfIsFive() {
        // floor((6 + 4²)/3 * 0.75) = floor(22/3 * 0.75) = floor(5.5) = 5
        assertEquals(5, DefenceMath.protectionEpf(4));
    }

    @Test
    void protectionTwoSinglePieceEpfIsTwo() {
        // floor((6 + 2²)/3 * 0.75) = floor(10/3 * 0.75) = floor(2.5) = 2
        assertEquals(2, DefenceMath.protectionEpf(2));
    }

    @Test
    void protectionFourOnAllFourPiecesClampsToTwenty() {
        // 4 pieces × 5 EPF = 20 (already at the clamp).
        int summed = DefenceMath.protectionEpf(4) * 4;
        assertEquals(20, DefenceMath.clampEpf(summed));
    }

    @Test
    void rawEpfClampsToTwenty() {
        // The era randomizes then clamps to 20; deterministically we clamp the
        // raw sum directly to 20 (the visible cap).  25 → 20.
        assertEquals(20, DefenceMath.clampEpf(25));
        assertEquals(20, DefenceMath.clampEpf(40));
    }

    @Test
    void protectionFourAllPiecesGivesEightyPercentReduction() {
        // EPF 20 → 0.04 * 20 = 0.80 reduction → 10 * 0.20 = 2.0 taken.
        assertEquals(2.0, DefenceMath.enchantReduced(10.0, 20), EPS);
    }

    @Test
    void epfZeroIsNoop() {
        assertEquals(10.0, DefenceMath.enchantReduced(10.0, 0), EPS);
    }

    /* ------------------------------------------------------------------ */
    /*  Absorption stage — applied last, soaks up to its amount            */
    /* ------------------------------------------------------------------ */

    @Test
    void absorptionSoaksUpToItsAmount() {
        // 4 absorption HP, 10 incoming → 6 taken.
        assertEquals(6.0, DefenceMath.absorptionReduced(10.0, 4.0), EPS);
    }

    @Test
    void absorptionNeverGoesNegative() {
        // 20 absorption HP, 10 incoming → 0 taken (not -10).
        assertEquals(0.0, DefenceMath.absorptionReduced(10.0, 20.0), EPS);
    }

    @Test
    void zeroAbsorptionIsNoop() {
        assertEquals(10.0, DefenceMath.absorptionReduced(10.0, 0.0), EPS);
    }

    /* ------------------------------------------------------------------ */
    /*  finalDamage — full pipeline in the era order                       */
    /* ------------------------------------------------------------------ */

    @Test
    void fullPipelineFullDiamondNoExtras() {
        // 10 raw, 20 armour, no resistance, no EPF, no absorption → 2.0
        assertEquals(2.0, DefenceMath.finalDamage(10.0, 20, 0, 0, 0.0), EPS);
    }

    @Test
    void fullPipelineArmourThenResistance() {
        // 10 raw → armour 20 → 2.0 → resistance I (×0.8) → 1.6
        assertEquals(1.6, DefenceMath.finalDamage(10.0, 20, 1, 0, 0.0), EPS);
    }

    @Test
    void fullPipelineArmourResistanceEnchant() {
        // 10 → armour 20 → 2.0 → resistance I ×0.8 → 1.6 → EPF 20 ×0.2 → 0.32
        assertEquals(0.32, DefenceMath.finalDamage(10.0, 20, 1, 20, 0.0), EPS);
    }

    @Test
    void fullPipelineAbsorptionAppliedLast() {
        // 10 → armour 20 → 2.0; absorption 1.5 → 0.5 taken.  Absorption must
        // apply AFTER armour/resistance/enchant, not before.
        assertEquals(0.5, DefenceMath.finalDamage(10.0, 20, 0, 0, 1.5), EPS);
    }

    @Test
    void fullPipelineOrderMattersAbsorptionDoesNotPreReduceArmour() {
        // If absorption were applied first: (10 - 4) * 0.2 = 1.2.  Era order
        // (armour first): 10*0.2 = 2.0, then -4 absorption clamps to 0.0.
        // This asserts the era order (absorption last) → 0.0, not 1.2.
        assertEquals(0.0, DefenceMath.finalDamage(10.0, 20, 0, 0, 4.0), EPS);
    }

    @Test
    void noReductionsReturnsRawDamage() {
        assertEquals(10.0, DefenceMath.finalDamage(10.0, 0, 0, 0, 0.0), EPS);
    }

    /* ------------------------------------------------------------------ */
    /*  Type modifier constants                                            */
    /* ------------------------------------------------------------------ */

    @Test
    void typeModifiersMatchEraValues() {
        assertEquals(0.75, DefenceMath.PROTECTION_MODIFIER, EPS);
        assertEquals(1.25, DefenceMath.FIRE_PROTECTION_MODIFIER, EPS);
        assertEquals(2.5, DefenceMath.FEATHER_FALLING_MODIFIER, EPS);
        assertEquals(1.5, DefenceMath.BLAST_PROTECTION_MODIFIER, EPS);
        assertEquals(1.5, DefenceMath.PROJECTILE_PROTECTION_MODIFIER, EPS);
    }

    @Test
    void featherFallingEpfMatchesEraFormula() {
        // floor((6 + 4²)/3 * 2.5) = floor(22/3 * 2.5) = floor(18.33) = 18
        assertEquals(18, DefenceMath.epf(4, DefenceMath.FEATHER_FALLING_MODIFIER));
    }
}
