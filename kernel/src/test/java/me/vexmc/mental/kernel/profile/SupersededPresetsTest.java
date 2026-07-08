package me.vexmc.mental.kernel.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The pristine-upgrade contract for the signature preset's opt-in to
 * speed-conformal knockback (2.3.2). An unedited pre-pace signature file — the
 * current values with {@code speed-scaling} absent, i.e. {@link PaceScaling#OFF}
 * — must be recognised as verbatim-superseded so {@code ConfigStore} regenerates
 * it in place with the new {@code mode: attacker} block; the current bundle
 * (pace ATTACKER) and any owner edit must NOT be.
 */
class SupersededPresetsTest {

    /** The signature as shipped 2.2.1 → 2.3.1: current values, pace scaling absent (OFF). */
    private static KnockbackProfile prePaceSignature() {
        KnockbackProfile s = Presets.SIGNATURE;
        return new KnockbackProfile(
                s.name(), s.displayName(), s.description(), s.base(), s.verticalMode(), s.extra(),
                s.wtapExtra(), s.friction(), s.limits(), s.air(), s.add(), s.rangeReduction(),
                s.sprintFactor(), s.combos(), s.meleeDelivery(), s.projectileDelivery(),
                s.resistance(), s.shieldBlockingCancels()); // 17-arg ⇒ PaceScaling.OFF
    }

    @Test
    void uneditedPrePaceSignatureUpgradesButTheCurrentBundleDoesNot() {
        KnockbackProfile prePace = prePaceSignature();
        assertTrue(prePace.paceScaling().mode() == PaceScaling.Mode.OFF,
                "the pre-pace signature must parse to OFF (the absent-block default)");

        // An unedited pre-pace file is verbatim-superseded → upgraded to gain pace.
        assertTrue(SupersededPresets.isSupersededVerbatim("signature", prePace),
                "an unedited pre-pace signature must be recognised for the pristine upgrade");

        // The current bundle already opts in — never re-flagged (no upgrade loop).
        assertFalse(SupersededPresets.isSupersededVerbatim("signature", Presets.SIGNATURE),
                "the current signature (pace attacker) is the target, not a superseded revision");

        // An owner edit (any value differs) is frozen forever.
        KnockbackProfile edited = new KnockbackProfile(
                prePace.name(), prePace.displayName(), prePace.description(),
                new KnockbackProfile.Push(0.4, prePace.base().vertical()), // tuned base horizontal
                prePace.verticalMode(), prePace.extra(), prePace.wtapExtra(), prePace.friction(),
                prePace.limits(), prePace.air(), prePace.add(), prePace.rangeReduction(),
                prePace.sprintFactor(), prePace.combos(), prePace.meleeDelivery(),
                prePace.projectileDelivery(), prePace.resistance(), prePace.shieldBlockingCancels());
        assertFalse(SupersededPresets.isSupersededVerbatim("signature", edited),
                "an owner-edited signature must never be touched");
    }

    /** The signature as shipped 2.4.0: current values with pace ATTACKER exponent 1.0. */
    private static KnockbackProfile pristine240Signature() {
        KnockbackProfile s = Presets.SIGNATURE;
        return new KnockbackProfile(
                s.name(), s.displayName(), s.description(), s.base(), s.verticalMode(), s.extra(),
                s.wtapExtra(), s.friction(), s.limits(), s.air(), s.add(), s.rangeReduction(),
                s.sprintFactor(), s.combos(), s.meleeDelivery(), s.projectileDelivery(),
                s.resistance(), s.shieldBlockingCancels(),
                new PaceScaling(PaceScaling.Mode.ATTACKER, 1.0, 0.5, 2.0));
    }

    @Test
    void pristine240SignatureRollsForwardToTheExponentTune() {
        KnockbackProfile pristine = pristine240Signature();
        assertEquals(1.0, pristine.paceScaling().exponent(), 0.0,
                "the 2.4.0 pristine signature shipped exponent 1.0");

        // An unedited 2.4.0 install (exponent 1.0) is verbatim-superseded → rolls
        // forward in place to gain the 0.95 tune.
        assertTrue(SupersededPresets.isSupersededVerbatim("signature", pristine),
                "an unedited exponent-1.0 signature must roll forward to the tune");

        // The current 0.95 bundle is the target, never re-flagged (no upgrade loop).
        assertFalse(SupersededPresets.isSupersededVerbatim("signature", Presets.SIGNATURE),
                "the current 0.95 signature is the target, not a superseded revision");
        assertEquals(0.95, Presets.SIGNATURE.paceScaling().exponent(), 0.0);

        // An owner edit of the exponent (any other value) is frozen forever.
        KnockbackProfile editedExponent = new KnockbackProfile(
                pristine.name(), pristine.displayName(), pristine.description(), pristine.base(),
                pristine.verticalMode(), pristine.extra(), pristine.wtapExtra(), pristine.friction(),
                pristine.limits(), pristine.air(), pristine.add(), pristine.rangeReduction(),
                pristine.sprintFactor(), pristine.combos(), pristine.meleeDelivery(),
                pristine.projectileDelivery(), pristine.resistance(), pristine.shieldBlockingCancels(),
                new PaceScaling(PaceScaling.Mode.ATTACKER, 0.8, 0.5, 2.0));
        assertFalse(SupersededPresets.isSupersededVerbatim("signature", editedExponent),
                "an owner-tuned exponent must never be touched");
    }

    /**
     * The 2.4.7 practice-floor round: each practice preset's pre-floor revision
     * (as shipped 1.8.0 → 2.4.6 — the current values with the −3.9 verticalMin
     * filler) is verbatim-superseded, the current 0.0-floor bundle is the
     * target (never re-flagged, no upgrade loop), and any owner edit is frozen.
     */
    @Test
    void uneditedPreFloorPracticePresetsUpgradeButTheCurrentBundlesDoNot() {
        for (String name : List.of("kohi", "mmc", "lunar", "minehq", "badlion")) {
            KnockbackProfile current = Presets.ALL.get(name);
            assertEquals(0.0, current.limits().verticalMin(), 0.0,
                    name + " must carry the 2.4.7 practice floor");

            KnockbackProfile preFloor = new KnockbackProfile(
                    current.name(), current.displayName(), current.description(),
                    current.base(), current.verticalMode(), current.extra(), current.wtapExtra(),
                    current.friction(),
                    new KnockbackProfile.Limits(
                            current.limits().vertical(), -3.9, current.limits().horizontal()),
                    current.air(), current.add(), current.rangeReduction(), current.sprintFactor(),
                    current.combos(), current.meleeDelivery(), current.projectileDelivery(),
                    current.resistance(), current.shieldBlockingCancels());
            assertTrue(SupersededPresets.isSupersededVerbatim(name, preFloor),
                    "an unedited pre-floor " + name + " must upgrade to the 2.4.7 floor");
            assertFalse(SupersededPresets.isSupersededVerbatim(name, current),
                    "the current " + name + " bundle is the target, not a superseded revision");

            // An owner edit (any tuned value) is frozen forever, old floor and all.
            KnockbackProfile edited = new KnockbackProfile(
                    preFloor.name(), preFloor.displayName(), preFloor.description(),
                    new KnockbackProfile.Push(
                            preFloor.base().horizontal() + 0.01, preFloor.base().vertical()),
                    preFloor.verticalMode(), preFloor.extra(), preFloor.wtapExtra(),
                    preFloor.friction(), preFloor.limits(), preFloor.air(), preFloor.add(),
                    preFloor.rangeReduction(), preFloor.sprintFactor(), preFloor.combos(),
                    preFloor.meleeDelivery(), preFloor.projectileDelivery(),
                    preFloor.resistance(), preFloor.shieldBlockingCancels());
            assertFalse(SupersededPresets.isSupersededVerbatim(name, edited),
                    "an owner-edited " + name + " must never be touched");
        }
    }
}
