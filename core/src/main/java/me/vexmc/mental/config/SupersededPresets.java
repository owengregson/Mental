package me.vexmc.mental.config;

import java.util.List;
import java.util.Map;
import me.vexmc.mental.config.KnockbackProfile.Friction;
import me.vexmc.mental.config.KnockbackProfile.Limits;
import me.vexmc.mental.config.KnockbackProfile.Push;
import me.vexmc.mental.config.KnockbackProfile.RangeReduction;
import me.vexmc.mental.config.KnockbackProfile.WtapExtra;
import org.jetbrains.annotations.NotNull;

/**
 * Every bundled-preset revision that later research corrected, exactly as it
 * shipped. A profile file on disk whose parsed values (and identity strings)
 * still match one of these was never tuned by the owner — it is the old
 * bundle verbatim, and {@link ConfigStore} upgrades it in place to the
 * corrected preset. A file that differs in any value is an owner edit and is
 * never touched.
 *
 * <p>The 2026-06-12 research round (docs/research/2026-06-12-archived-
 * server-values.md) replaced the community-remake mmc and lunar values with
 * the real servers' archived configs and corrected kohi's era model
 * (1.7.10 lineage → ledger combos); the revisions below are what those
 * presets shipped between 1.3.0 and 1.7.0.</p>
 */
final class SupersededPresets {

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

    private static final Map<String, List<KnockbackProfile>> BY_PRESET = Map.of(
            "kohi", List.of(KOHI_1_3),
            "mmc", List.of(MMC_1_3),
            "lunar", List.of(LUNAR_1_3));

    private SupersededPresets() {}

    /** The superseded shipped revisions of {@code preset}; empty when none. */
    static @NotNull List<KnockbackProfile> of(@NotNull String preset) {
        return BY_PRESET.getOrDefault(preset, List.of());
    }

    /**
     * Whether {@code parsed} is one of {@code preset}'s superseded bundled
     * revisions verbatim — values AND identity strings, so a file whose
     * display name or description was customized counts as edited.
     */
    static boolean isSupersededVerbatim(@NotNull String preset, @NotNull KnockbackProfile parsed) {
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
