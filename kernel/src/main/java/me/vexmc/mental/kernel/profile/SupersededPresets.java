package me.vexmc.mental.kernel.profile;

import java.util.List;
import java.util.Map;
import me.vexmc.mental.kernel.profile.KnockbackProfile.Friction;
import me.vexmc.mental.kernel.profile.KnockbackProfile.Limits;
import me.vexmc.mental.kernel.profile.KnockbackProfile.Push;
import me.vexmc.mental.kernel.profile.KnockbackProfile.RangeReduction;
import me.vexmc.mental.kernel.profile.KnockbackProfile.WtapExtra;

/**
 * Every bundled-preset revision that later research corrected, exactly as it
 * shipped. A profile file on disk whose parsed values (and identity strings)
 * still match one of these was never tuned by the owner — it is the old
 * bundle verbatim, and {@code ConfigStore} upgrades it in place to the
 * corrected preset. A file that differs in any value is an owner edit and is
 * never touched.
 *
 * <p>The 2026-06-12 research round (docs/research/2026-06-12-archived-
 * server-values.md) replaced the community-remake mmc and lunar values with
 * the real servers' archived configs and corrected kohi's era model
 * (1.7.10 lineage → ledger combos); the revisions below are what those
 * presets shipped between 1.3.0 and 1.7.0.</p>
 *
 * <p>The 2.4.7 downward-knock round floors the five practice presets'
 * {@code limits.verticalMin} at {@code 0.0} (the archives carried no vertical
 * floor knob — {@code −3.9} was Mental's schema filler, and it let a deep
 * falling ledger vy ship a DOWNWARD combo knock); the {@code *_1_8} revisions
 * below are those presets exactly as shipped 1.8.0 → 2.4.6.</p>
 *
 * <p>The 2.4.8 round extends the floor to legacy-1.7, legacy-1.8 and custom
 * (the owner reported flat-ground downward knocks on the legacy presets too —
 * an owner directive that deliberately trades away the era-authentic
 * long-fall negative); the {@code *_2_4_7} revisions below are those presets
 * exactly as shipped through 2.4.7 (unchanged since they first bundled).</p>
 */
public final class SupersededPresets {

    /** kohi as shipped 1.3.0–1.7.0: correct values, 1.8-era combos/resistance. */
    private static final KnockbackProfile KOHI_1_3 = new KnockbackProfile(
            "kohi",
            "Kohi",
            "The canonical Kohi/HCF values — lower base, smaller per-level bonus"
                    + " (0.425/0.085), flat delivery.",
            new Push(0.35, 0.35),
            VerticalMode.ADD,
            new Push(0.425, 0.085),
            new WtapExtra(false, 0.425, 0.085),
            new Friction(0.5, 0.5, 0.5),
            new Limits(0.4, -3.9, -1.0),
            new Push(1.0, 1.0),
            new Push(0.0, 0.0),
            RangeReduction.DISABLED,
            1.0,
            false,
            KnockbackDelivery.TRACKER,
            KnockbackDelivery.TRACKER,
            ResistancePolicy.LEGACY,
            true);

    /** mmc as shipped 1.3.0–1.7.0: the fanmade ClubSpigot remake reconstruction. */
    private static final KnockbackProfile MMC_1_3 = new KnockbackProfile(
            "mmc",
            "MMC",
            "Community remake of the Minemen Club feel: assigned vertical,"
                    + " distance taper, flat delivery.",
            new Push(0.38488, 0.25635),
            VerticalMode.SET,
            new Push(0.5, 0.1),
            new WtapExtra(false, 0.5, 0.1),
            new Friction(0.5248, 0.5248, 0.5248),
            new Limits(4.0, -3.9, -1.0),
            new Push(1.0, 1.0),
            new Push(0.0, 0.0),
            new RangeReduction(true, 3.0, 0.025, 1.2, 0.12),
            1.0,
            false,
            KnockbackDelivery.IMMEDIATE,
            KnockbackDelivery.IMMEDIATE,
            ResistancePolicy.LEGACY,
            true);

    /** lunar as shipped 1.3.0–1.7.0: the community recreation's values. */
    private static final KnockbackProfile LUNAR_1_3 = new KnockbackProfile(
            "lunar",
            "Lunar",
            "Community recreation of Lunar-network-era knockback — higher"
                    + " friction survival, soft vertical.",
            new Push(0.46, 0.3535),
            VerticalMode.ADD,
            new Push(0.138, 0.0),
            new WtapExtra(false, 0.138, 0.0),
            new Friction(0.6667, 0.6667, 0.6667),
            new Limits(0.3535, -3.9, -1.0),
            new Push(1.0, 1.0),
            new Push(0.0, 0.0),
            RangeReduction.DISABLED,
            1.0,
            false,
            KnockbackDelivery.TRACKER,
            KnockbackDelivery.TRACKER,
            ResistancePolicy.LEGACY,
            true);

    /** kohi as shipped 1.8.0 → 2.4.6: the archived values with the −3.9 verticalMin filler. */
    private static final KnockbackProfile KOHI_1_8 = new KnockbackProfile(
            "kohi",
            "Kohi",
            "The canonical Kohi/HCF values — lower base, smaller per-level bonus"
                    + " (0.425/0.085), 1.7.10 ledger combos.",
            new Push(0.35, 0.35),
            VerticalMode.ADD,
            new Push(0.425, 0.085),
            new WtapExtra(false, 0.425, 0.085),
            new Friction(0.5, 0.5, 0.5),
            new Limits(0.4, -3.9, -1.0),
            new Push(1.0, 1.0),
            new Push(0.0, 0.0),
            RangeReduction.DISABLED,
            1.0,
            true,
            KnockbackDelivery.TRACKER,
            KnockbackDelivery.TRACKER,
            ResistancePolicy.NONE,
            true);

    /** mmc as shipped 1.8.0 → 2.4.6: the archived values with the −3.9 verticalMin filler. */
    private static final KnockbackProfile MMC_1_8 = new KnockbackProfile(
            "mmc",
            "MMC",
            "Minemen Club's archived dev123 (2017) values — soft base, full"
                    + " vanilla sprint bonus, flat 1.8 delivery.",
            new Push(0.32, 0.32),
            VerticalMode.ADD,
            new Push(0.5, 0.1),
            new WtapExtra(false, 0.5, 0.1),
            new Friction(0.5556, 0.5556, 0.5556),
            new Limits(0.4, -3.9, -1.0),
            new Push(1.0, 1.0),
            new Push(0.0, 0.0),
            RangeReduction.DISABLED,
            1.0,
            false,
            KnockbackDelivery.IMMEDIATE,
            KnockbackDelivery.TRACKER,
            ResistancePolicy.NONE,
            true);

    /** lunar as shipped 1.8.0 → 2.4.6: the archived values with the −3.9 verticalMin filler. */
    private static final KnockbackProfile LUNAR_1_8 = new KnockbackProfile(
            "lunar",
            "Lunar",
            "Lunar Network's archived S5 values — heavy base, high residual"
                    + " survival, weak sprint differential.",
            new Push(0.54, 0.44),
            VerticalMode.ADD,
            new Push(0.38, 0.0),
            new WtapExtra(false, 0.38, 0.0),
            new Friction(0.6849, 0.7634, 0.6849),
            new Limits(0.361735, -3.9, -1.0),
            new Push(1.0, 1.0),
            new Push(0.0, 0.0),
            RangeReduction.DISABLED,
            1.0,
            false,
            KnockbackDelivery.IMMEDIATE,
            KnockbackDelivery.TRACKER,
            ResistancePolicy.NONE,
            true);

    /** minehq as shipped 1.8.0 → 2.4.6: the archived values with the −3.9 verticalMin filler. */
    private static final KnockbackProfile MINEHQ_1_8 = new KnockbackProfile(
            "minehq",
            "MineHQ",
            "MineHQ's archived HCF values — between Kohi and vanilla, 1.7.10 ledger combos.",
            new Push(0.36, 0.36),
            VerticalMode.ADD,
            new Push(0.45, 0.09),
            new WtapExtra(false, 0.45, 0.09),
            new Friction(0.5, 0.5, 0.5),
            new Limits(0.4, -3.9, -1.0),
            new Push(1.0, 1.0),
            new Push(0.0, 0.0),
            RangeReduction.DISABLED,
            1.0,
            true,
            KnockbackDelivery.TRACKER,
            KnockbackDelivery.TRACKER,
            ResistancePolicy.NONE,
            true);

    /** badlion as shipped 1.8.0 → 2.4.6: the archived values with the −3.9 verticalMin filler. */
    private static final KnockbackProfile BADLION_1_8 = new KnockbackProfile(
            "badlion",
            "Badlion",
            "Badlion's archived NoDebuff values — soft base 0.34, strong sprint"
                    + " differential, 1.7 ledger combos.",
            new Push(0.34, 0.34),
            VerticalMode.ADD,
            new Push(0.48, 0.085),
            new WtapExtra(false, 0.48, 0.085),
            new Friction(0.5, 0.5, 0.5),
            new Limits(0.4, -3.9, -1.0),
            new Push(1.0, 1.0),
            new Push(0.0, 0.0),
            RangeReduction.DISABLED,
            1.0,
            true,
            KnockbackDelivery.TRACKER,
            KnockbackDelivery.TRACKER,
            ResistancePolicy.NONE,
            true);

    /**
     * signature as shipped in 2.2.0: velt verbatim plus only the horizontal
     * pocket trim ({@code air.horizontal 0.92}). 2.2.1 added the vertical
     * tuning ({@code base.vertical 0.365}, {@code air.vertical 0.98}) the owner
     * found held combos best, so an unedited 2.2.0 file upgrades in place.
     */
    private static final KnockbackProfile SIGNATURE_2_2_0 = new KnockbackProfile(
            "signature",
            "Signature",
            "Mental's signature feel — velt's residual wipe and pinned 0.36"
                    + " vertical, with airborne combo hits trimmed 8% to hold"
                    + " the reach pocket.",
            new Push(0.325, 0.36),
            VerticalMode.ADD,
            new Push(0.5, 0.0),
            new WtapExtra(false, 0.5, 0.0),
            new Friction(0.1, 0.1, 0.1),
            new Limits(0.36, -3.9, -1.0),
            new Push(0.92, 1.0),
            new Push(0.0, 0.0),
            RangeReduction.DISABLED,
            1.0,
            false,
            KnockbackDelivery.TRACKER,
            KnockbackDelivery.TRACKER,
            ResistancePolicy.NONE,
            true);

    /**
     * signature as shipped 2.2.1 → 2.3.1: the current velt-derivative values
     * (air {@code (0.92, 0.98)}, base vertical {@code 0.365}) but WITHOUT
     * speed-conformal knockback — the {@code speed-scaling} block did not exist,
     * so an unedited file parses to {@link PaceScaling#OFF} (the pace default
     * of the 18-arg constructor). 2.4.0 opts the signature preset into pace
     * scaling ({@code mode: attacker}, the owner's ask), so an unedited pre-pace
     * signature file upgrades in place to gain the block. Its values and identity
     * strings match the current bundle except for the pace block AND (from 2.4.1)
     * the exponent tune — see {@link #SIGNATURE_2_4_0}.
     */
    private static final KnockbackProfile SIGNATURE_2_2_1 = new KnockbackProfile(
            "signature",
            "Signature",
            "Mental's signature feel — velt's residual wipe and full sprint"
                    + " horizontal, tuned to hold the combo reach pocket (airborne"
                    + " hits trimmed h x0.92 / v x0.98, base vertical 0.365).",
            new Push(0.325, 0.365),
            VerticalMode.ADD,
            new Push(0.5, 0.0),
            new WtapExtra(false, 0.5, 0.0),
            new Friction(0.1, 0.1, 0.1),
            new Limits(0.36, -3.9, -1.0),
            new Push(0.92, 0.98),
            new Push(0.0, 0.0),
            RangeReduction.DISABLED,
            1.0,
            false,
            KnockbackDelivery.TRACKER,
            KnockbackDelivery.TRACKER,
            ResistancePolicy.NONE,
            true);

    /**
     * signature as shipped in 2.4.0: the current values WITH speed-conformal
     * knockback at the fully-conformal exponent {@code 1.0} — the pace opt-in as
     * it first shipped. 2.4.1 tunes the exponent to {@code 0.95} (the owner's
     * Speed-III feel), so an unedited 2.4.0 install rolls forward in place; the
     * identity strings are byte-identical to the current bundle, and only the
     * exponent separates this superseded revision from it.
     */
    private static final KnockbackProfile SIGNATURE_2_4_0 = new KnockbackProfile(
            "signature",
            "Signature",
            "Mental's signature feel — velt's residual wipe and full sprint"
                    + " horizontal, tuned to hold the combo reach pocket (airborne"
                    + " hits trimmed h x0.92 / v x0.98, base vertical 0.365).",
            new Push(0.325, 0.365),
            VerticalMode.ADD,
            new Push(0.5, 0.0),
            new WtapExtra(false, 0.5, 0.0),
            new Friction(0.1, 0.1, 0.1),
            new Limits(0.36, -3.9, -1.0),
            new Push(0.92, 0.98),
            new Push(0.0, 0.0),
            RangeReduction.DISABLED,
            1.0,
            false,
            KnockbackDelivery.TRACKER,
            KnockbackDelivery.TRACKER,
            ResistancePolicy.NONE,
            true,
            new PaceScaling(PaceScaling.Mode.ATTACKER, 1.0, 0.5, 2.0));

    /**
     * legacy-1.7 as shipped through 2.4.7: the era values with the unfloored
     * −3.9 verticalMin filler. 2.4.8 floors the final vertical at 0.0 (owner
     * directive — flat-ground downward knocks were reported on legacy too),
     * so an unedited file upgrades in place; an older or owner-tuned file
     * simply never matches and stays frozen.
     */
    private static final KnockbackProfile LEGACY17_2_4_7 = new KnockbackProfile(
            "legacy-1.7",
            "Legacy 1.7",
            "The 1.7.10 combat model: vanilla-era values with ledger combos.",
            new Push(0.4, 0.4),
            VerticalMode.ADD,
            new Push(0.5, 0.1),
            new WtapExtra(false, 0.5, 0.1),
            new Friction(0.5, 0.5, 0.5),
            new Limits(0.4, -3.9, -1.0),
            new Push(1.0, 1.0),
            new Push(0.0, 0.0),
            RangeReduction.DISABLED,
            1.0,
            true,
            KnockbackDelivery.TRACKER,
            KnockbackDelivery.TRACKER,
            ResistancePolicy.NONE,
            true);

    /** legacy-1.8 as shipped through 2.4.7 — the unfloored twin of {@link #LEGACY17_2_4_7}. */
    private static final KnockbackProfile LEGACY18_2_4_7 = new KnockbackProfile(
            "legacy-1.8",
            "Legacy 1.8",
            "The 1.8.9 combat model: identical math, flat send-then-revert delivery.",
            new Push(0.4, 0.4),
            VerticalMode.ADD,
            new Push(0.5, 0.1),
            new WtapExtra(false, 0.5, 0.1),
            new Friction(0.5, 0.5, 0.5),
            new Limits(0.4, -3.9, -1.0),
            new Push(1.0, 1.0),
            new Push(0.0, 0.0),
            RangeReduction.DISABLED,
            1.0,
            false,
            KnockbackDelivery.IMMEDIATE,
            KnockbackDelivery.TRACKER,
            ResistancePolicy.LEGACY,
            true);

    /**
     * custom as shipped through 2.4.7 — legacy-1.7 values verbatim under the
     * custom identity ("ships as legacy-1.7 values"), so an unedited
     * custom.yml follows the 2.4.8 floor exactly like its template; the
     * moment the owner tunes anything it is theirs forever.
     */
    private static final KnockbackProfile CUSTOM_2_4_7 = new KnockbackProfile(
            "custom",
            "Custom",
            "Your own knockback tuning — edit profiles/custom.yml.",
            new Push(0.4, 0.4),
            VerticalMode.ADD,
            new Push(0.5, 0.1),
            new WtapExtra(false, 0.5, 0.1),
            new Friction(0.5, 0.5, 0.5),
            new Limits(0.4, -3.9, -1.0),
            new Push(1.0, 1.0),
            new Push(0.0, 0.0),
            RangeReduction.DISABLED,
            1.0,
            true,
            KnockbackDelivery.TRACKER,
            KnockbackDelivery.TRACKER,
            ResistancePolicy.NONE,
            true);

    private static final Map<String, List<KnockbackProfile>> BY_PRESET = Map.of(
            "kohi", List.of(KOHI_1_3, KOHI_1_8),
            "mmc", List.of(MMC_1_3, MMC_1_8),
            "lunar", List.of(LUNAR_1_3, LUNAR_1_8),
            "minehq", List.of(MINEHQ_1_8),
            "badlion", List.of(BADLION_1_8),
            "signature", List.of(SIGNATURE_2_2_0, SIGNATURE_2_2_1, SIGNATURE_2_4_0),
            "legacy-1.7", List.of(LEGACY17_2_4_7),
            "legacy-1.8", List.of(LEGACY18_2_4_7),
            "custom", List.of(CUSTOM_2_4_7));

    private SupersededPresets() {}

    /** The superseded shipped revisions of {@code preset}; empty when none. */
    public static List<KnockbackProfile> of(String preset) {
        return BY_PRESET.getOrDefault(preset, List.of());
    }

    /**
     * Whether {@code parsed} is one of {@code preset}'s superseded bundled
     * revisions verbatim — values AND identity strings, so a file whose
     * display name or description was customized counts as edited.
     */
    public static boolean isSupersededVerbatim(String preset, KnockbackProfile parsed) {
        for (KnockbackProfile revision : of(preset)) {
            if (parsed.sameValues(revision)
                    && parsed.displayName().equals(revision.displayName())
                    && parsed.description().equals(revision.description())) {
                return true;
            }
        }
        return false;
    }
}
