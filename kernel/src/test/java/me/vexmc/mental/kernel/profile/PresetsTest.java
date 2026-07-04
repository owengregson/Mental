package me.vexmc.mental.kernel.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The value-table half of the original
 * {@code MentalConfigTest.bundledPresetsCarryTheirCanonicalValues}: every
 * assertion is byte-identical, but the profiles come from the kernel's
 * canonical constants instead of the parsed bundled YAML — Phase 3's parser
 * must reproduce these constants from the YAML to pass its own half.
 */
class PresetsTest {

    @Test
    void bundledPresetsCarryTheirCanonicalValues() {
        Map<String, KnockbackProfile> profiles = Presets.ALL;

        KnockbackProfile legacy17 = profiles.get("legacy-1.7");
        assertNotNull(legacy17);
        // The full tracker stamp, NOT the later-joiner decay: vanilla's
        // tracker wire was connection-order bimodal, and the dominant mode
        // shipped undecayed (measured on real 1.7.10, both join orders).
        assertEquals(KnockbackDelivery.TRACKER, legacy17.meleeDelivery());
        assertEquals(KnockbackDelivery.TRACKER, legacy17.projectileDelivery());

        KnockbackProfile legacy18 = profiles.get("legacy-1.8");
        assertNotNull(legacy18);
        assertFalse(legacy18.combos());
        assertEquals(ResistancePolicy.LEGACY, legacy18.resistance());
        assertEquals(KnockbackProfile.LEGACY_17.base(), legacy18.base());
        assertEquals(KnockbackProfile.LEGACY_17.extra(), legacy18.extra());
        assertEquals(KnockbackDelivery.IMMEDIATE, legacy18.meleeDelivery());
        assertEquals(KnockbackDelivery.TRACKER, legacy18.projectileDelivery());

        // kohi == the archived kohi2016 values (friction divisor 2.0 → 0.5),
        // on the 1.7.10 era model: ledger combos, tracker wire, no
        // resistance roll (the 1.7 item pool had nothing resistant).
        KnockbackProfile kohi = profiles.get("kohi");
        assertNotNull(kohi);
        assertEquals(new KnockbackProfile.Push(0.35, 0.35), kohi.base());
        assertEquals(new KnockbackProfile.Push(0.425, 0.085), kohi.extra());
        assertEquals(new KnockbackProfile.Friction(0.5, 0.5, 0.5), kohi.friction());
        assertEquals(0.4, kohi.limits().vertical());
        assertEquals(VerticalMode.ADD, kohi.verticalMode());
        assertTrue(kohi.combos());
        assertEquals(KnockbackDelivery.TRACKER, kohi.meleeDelivery());
        assertEquals(KnockbackDelivery.TRACKER, kohi.projectileDelivery());
        assertEquals(ResistancePolicy.NONE, kohi.resistance());

        // mmc == the archived dev123.minemen.club (2017) values, confirmed
        // byte-identical across two independent archives: friction divisor
        // 1.8 → 0.5556, vanilla ADD shape, full vanilla sprint bonus. The
        // remake-derived SET vertical and distance taper are superseded.
        KnockbackProfile mmc = profiles.get("mmc");
        assertNotNull(mmc);
        assertEquals(KnockbackDelivery.IMMEDIATE, mmc.meleeDelivery());
        assertEquals(KnockbackDelivery.TRACKER, mmc.projectileDelivery());
        assertEquals(VerticalMode.ADD, mmc.verticalMode());
        assertEquals(new KnockbackProfile.Push(0.32, 0.32), mmc.base());
        assertEquals(new KnockbackProfile.Push(0.5, 0.1), mmc.extra());
        assertEquals(new KnockbackProfile.Friction(0.5556, 0.5556, 0.5556), mmc.friction());
        assertFalse(mmc.rangeReduction().enabled());
        assertEquals(0.4, mmc.limits().vertical());
        assertFalse(mmc.combos());
        assertEquals(ResistancePolicy.NONE, mmc.resistance());

        // lunar == the archived Lunar S5 values, confirmed byte-identical
        // across two independent archives: split friction (÷1.46 h, ÷1.31 v),
        // heavy base, weak sprint differential, cap below the base vertical.
        KnockbackProfile lunar = profiles.get("lunar");
        assertNotNull(lunar);
        assertEquals(new KnockbackProfile.Push(0.54, 0.44), lunar.base());
        assertEquals(new KnockbackProfile.Push(0.38, 0.0), lunar.extra());
        assertEquals(new KnockbackProfile.Friction(0.6849, 0.7634, 0.6849), lunar.friction());
        assertEquals(0.361735, lunar.limits().vertical());
        assertFalse(lunar.combos());
        assertEquals(KnockbackDelivery.IMMEDIATE, lunar.meleeDelivery());
        assertEquals(KnockbackDelivery.TRACKER, lunar.projectileDelivery());
        assertEquals(ResistancePolicy.NONE, lunar.resistance());

        // minehq == the archived MineHQ values: between kohi and vanilla,
        // 1.7.10 era model like kohi.
        KnockbackProfile minehq = profiles.get("minehq");
        assertNotNull(minehq);
        assertEquals(new KnockbackProfile.Push(0.36, 0.36), minehq.base());
        assertEquals(new KnockbackProfile.Push(0.45, 0.09), minehq.extra());
        assertEquals(new KnockbackProfile.Friction(0.5, 0.5, 0.5), minehq.friction());
        assertEquals(0.4, minehq.limits().vertical());
        assertTrue(minehq.combos());
        assertEquals(KnockbackDelivery.TRACKER, minehq.meleeDelivery());

        // badlion == the archived NoDebuff/PotPvP values (both archives):
        // softest base of the practice set, on the 1.7 back-end Badlion ran
        // through its NoDebuff prime (ledger combos, tracker wire).
        KnockbackProfile badlion = profiles.get("badlion");
        assertNotNull(badlion);
        assertEquals(new KnockbackProfile.Push(0.34, 0.34), badlion.base());
        assertEquals(new KnockbackProfile.Push(0.48, 0.085), badlion.extra());
        assertEquals(new KnockbackProfile.Friction(0.5, 0.5, 0.5), badlion.friction());
        assertEquals(0.4, badlion.limits().vertical());
        assertTrue(badlion.combos());
        assertEquals(KnockbackDelivery.TRACKER, badlion.meleeDelivery());

        // velt == the archived VeltPvP values: friction divisor 10 → 0.1
        // residual wipe, fixed 0.36 vertical (cap == base), zero sprint
        // vertical — the late-era "dead consistent" practice shape.
        KnockbackProfile velt = profiles.get("velt");
        assertNotNull(velt);
        assertEquals(new KnockbackProfile.Push(0.325, 0.36), velt.base());
        assertEquals(new KnockbackProfile.Push(0.5, 0.0), velt.extra());
        assertEquals(new KnockbackProfile.Friction(0.1, 0.1, 0.1), velt.friction());
        assertEquals(0.36, velt.limits().vertical());
        assertFalse(velt.combos());
        // velt does not correct the airborne over-travel — its air multiplier
        // is identity. signature is the derivative that does.
        assertEquals(new KnockbackProfile.Push(1.0, 1.0), velt.air());

        // signature == Mental's own velt derivative, tuned by playtesting:
        // velt's residual wipe, sprint horizontal, and 0.36 vertical cap, with
        // three changes — air.horizontal 0.92 (the airborne pocket trim),
        // base.vertical 0.365 (a touch above the cap, so descending hits keep
        // more lift), and air.vertical 0.98 (the airborne vertical trim).
        KnockbackProfile signature = profiles.get("signature");
        assertNotNull(signature);
        assertEquals(new KnockbackProfile.Push(0.325, 0.365), signature.base());
        assertEquals(new KnockbackProfile.Push(0.92, 0.98), signature.air());
        assertEquals(velt.extra(), signature.extra());
        assertEquals(velt.friction(), signature.friction());
        assertEquals(velt.limits(), signature.limits());   // cap stays 0.36
        assertEquals(velt.verticalMode(), signature.verticalMode());
        assertEquals(velt.combos(), signature.combos());
        assertEquals(velt.meleeDelivery(), signature.meleeDelivery());
        assertEquals(velt.resistance(), signature.resistance());
        // signature is the ONE preset that opts into speed-conformal knockback
        // (the owner's ask, and Mental's own preset); exponent 0.95 is the 2.4.1
        // Speed-III feel tune.
        assertEquals(new PaceScaling(PaceScaling.Mode.ATTACKER, 0.95, 0.5, 2.0), signature.paceScaling());

        // Every OTHER preset stays OFF — archived-server presets are historical
        // records, and OFF is the era-exact no-op.
        for (KnockbackProfile profile : profiles.values()) {
            if (!"signature".equals(profile.name())) {
                assertEquals(PaceScaling.OFF, profile.paceScaling(),
                        () -> profile.name() + " must not opt into pace scaling");
            }
        }

        // custom ships as legacy-1.7 values — selecting it changes nothing
        // until the owner edits the file.
        KnockbackProfile custom = profiles.get("custom");
        assertNotNull(custom);
        assertTrue(custom.sameValues(KnockbackProfile.LEGACY_17));
    }
}
