package me.vexmc.mental.v5.feature.damage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import me.vexmc.mental.kernel.coexist.MechanicToken;
import me.vexmc.mental.kernel.model.HitContext;
import me.vexmc.mental.kernel.model.HitId;
import me.vexmc.mental.kernel.model.HitSource;
import me.vexmc.mental.kernel.model.SprintVerdict;
import me.vexmc.mental.kernel.model.TickStamp;
import org.junit.jupiter.api.Test;

/**
 * The fast-path composition arithmetic (fixed legacy order, era pins) and the
 * single crit/tool-damage verdict source shared with the crit fallback.
 */
class DamageShaperTest {

    private static final UUID ATTACKER = UUID.randomUUID();

    private static HitContext context(UUID attacker) {
        return new HitContext(
                new HitId(1), new HitSource.Melee(), attacker, UUID.randomUUID(),
                new SprintVerdict(false, null, TickStamp.NO_TICK), false, false, null, TickStamp.NO_TICK);
    }

    /* --------------------------- composition pins --------------------------- */

    @Test
    void legacyDiamondSwordSharpnessFiveIsFourteenPointTwoFive() {
        // Diamond sword legacy base 8.0 + Sharpness 1.25×5 = 6.25; no crit, no potion.
        assertEquals(14.25, DamageShaper.composeLegacy(8.0, -1, -1, false, false, 5), 1e-9,
                "the era pin: sharpness-5 diamond legacy-composes to 14.25");
    }

    @Test
    void legacyCritMultipliesTheWeaponBaseBeforeTheSharpnessAdditive() {
        // (8 × 1.5) + 6.25 = 18.25 — crit never multiplies the Sharpness additive.
        assertEquals(18.25, DamageShaper.composeLegacy(8.0, -1, -1, false, true, 5), 1e-9);
    }

    @Test
    void legacyStrengthOneMultipliesTheWeaponBaseBeforeSharpness() {
        // Strength I (amp 0) ×3.5 on the base 8 = 28, + Sharpness 6.25 = 34.25.
        assertEquals(34.25, DamageShaper.composeLegacy(8.0, 0, -1, true, false, 5), 1e-9);
    }

    @Test
    void vanillaShapeUsesNinePointNineSharpnessAndSprintExcludesCrit() {
        // Attribute base 7 (modern diamond sword) + 1.9 sharpness (1 + 0.5×4 = 3) = 10.
        assertEquals(10.0, DamageShaper.composeVanillaShape(7.0, false, false, 5), 1e-9);
        // Crit while sprinting is excluded (1.9 rule) — no ×1.5.
        assertEquals(10.0, DamageShaper.composeVanillaShape(7.0, true, true, 5), 1e-9);
        // Crit while not sprinting applies: (7 × 1.5) + 3 = 13.5.
        assertEquals(13.5, DamageShaper.composeVanillaShape(7.0, true, false, 5), 1e-9);
    }

    /* --------------------------- shared verdict --------------------------- */

    @Test
    void ownershipResolvesCritAndToolDamageFromTheOneHitContext() {
        DamageOwnership mentalOwns = new DamageOwnership((token, id) -> true);
        assertTrue(mentalOwns.mentalOwnsCriticalHits(context(ATTACKER)));
        assertTrue(mentalOwns.mentalOwnsToolDamage(context(ATTACKER)));
        assertFalse(mentalOwns.ocmShapesDamage(context(ATTACKER)));

        // OCM owns crit only → still shapes damage (the retired HitApplier OR).
        DamageOwnership ocmOwnsCrit = new DamageOwnership(
                (token, id) -> token != MechanicToken.CRITICAL_HITS);
        assertFalse(ocmOwnsCrit.mentalOwnsCriticalHits(context(ATTACKER)));
        assertTrue(ocmOwnsCrit.mentalOwnsToolDamage(context(ATTACKER)));
        assertTrue(ocmOwnsCrit.ocmShapesDamage(context(ATTACKER)));
    }

    @Test
    void aNullAttackerIsMentalOwned() {
        DamageOwnership ocmOwnsEverything = new DamageOwnership((token, id) -> false);
        assertTrue(ocmOwnsEverything.mentalOwnsCriticalHits(context(null)));
        assertFalse(ocmOwnsEverything.ocmShapesDamage(context(null)));
    }

    @Test
    void theFastPathAndTheCritFallbackShareTheOneVerdictSource() {
        // The forgotten-gate bug class is structurally dead: the shaper (fast path)
        // and the crit fallback (vanilla path) are constructed from ONE instance.
        DamageOwnership source = new DamageOwnership((token, id) -> true);
        DamageShaper shaper = new DamageShaper(source);
        CritFallbackUnit fallback = new CritFallbackUnit(source, () -> null);
        assertSame(shaper.ownership(), fallback.ownership(),
                "both damage paths must resolve ownership through the SAME DamageOwnership");
    }
}
