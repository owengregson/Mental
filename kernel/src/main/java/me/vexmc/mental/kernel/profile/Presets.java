package me.vexmc.mental.kernel.profile;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import me.vexmc.mental.kernel.profile.KnockbackProfile.Friction;
import me.vexmc.mental.kernel.profile.KnockbackProfile.Limits;
import me.vexmc.mental.kernel.profile.KnockbackProfile.Push;
import me.vexmc.mental.kernel.profile.KnockbackProfile.RangeReduction;
import me.vexmc.mental.kernel.profile.KnockbackProfile.WtapExtra;

/**
 * The canonical bundled preset values, as Java constants — the single source
 * of truth the bundled YAML files and Phase 3's parser must reproduce
 * byte-identically (the parser's acceptance spec). Provenance per preset:
 * the 2026-06-12 archived-server research round
 * (docs/research/2026-06-12-archived-server-values.md) — kohi/minehq/badlion
 * from the 1.7-lineage ledger archives, mmc from the real dev123.minemen.club
 * (2017) config, lunar from the real archived Lunar S5 values, velt from the
 * archived VeltPvP values, and signature as Mental's own playtested velt
 * derivative.
 */
public final class Presets {

    private Presets() {}

    /** The 1.8.9 combat model: identical math, flat send-then-revert delivery. */
    public static final KnockbackProfile LEGACY_18 = new KnockbackProfile(
            "legacy-1.8",
            "Legacy 1.8",
            "The 1.8.9 combat model: identical math, flat send-then-revert delivery.",
            new Push(0.4, 0.4),
            VerticalMode.ADD,
            new Push(0.5, 0.1),
            new WtapExtra(false, 0.5, 0.1),
            new Friction(0.5, 0.5, 0.5),
            new Limits(0.4, 0.0, -1.0), // verticalMin 0.0 — the 2.4.8 owner floor (see LEGACY_17)
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
     * The archived kohi2016 values (friction divisor 2.0 → 0.5), on the
     * 1.7.10 era model: ledger combos, tracker wire, no resistance roll
     * (the 1.7 item pool had nothing resistant).
     */
    public static final KnockbackProfile KOHI = new KnockbackProfile(
            "kohi",
            "Kohi",
            "The canonical Kohi/HCF values — lower base, smaller per-level bonus"
                    + " (0.425/0.085), 1.7.10 ledger combos.",
            new Push(0.35, 0.35),
            VerticalMode.ADD,
            new Push(0.425, 0.085),
            new WtapExtra(false, 0.425, 0.085),
            new Friction(0.5, 0.5, 0.5),
            // verticalMin 0.0 — the 2.4.7 practice floor. The archived configs
            // carried NO vertical floor knob (−3.9 was Mental's schema filler,
            // a no-op), and the ADD vertical (vy × friction.y + base.vertical)
            // ships DOWNWARD once a falling ledger vy passes
            // −(base + extra) / friction.y (kohi sprint: −0.87) — a leak the
            // real servers' true physics never reached in flat play (every
            // measured era hit-2 vertical is positive; compendium §1.4). velt
            // and signature stay unfloored (friction.y 0.1 puts the threshold
            // past the −3.92 decay terminal); legacy-1.7/1.8 joined the floor
            // in 2.4.8 (owner-reported flat-ground leaks there too — see
            // KnockbackProfile.LEGACY_17).
            new Limits(0.4, 0.0, -1.0),
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
     * The archived dev123.minemen.club (2017) values, confirmed byte-identical
     * across two independent archives: friction divisor 1.8 → 0.5556, vanilla
     * ADD shape, full vanilla sprint bonus. The remake-derived SET vertical
     * and distance taper are superseded.
     */
    public static final KnockbackProfile MMC = new KnockbackProfile(
            "mmc",
            "MMC",
            "Minemen Club's archived dev123 (2017) values — soft base, full"
                    + " vanilla sprint bonus, flat 1.8 delivery.",
            new Push(0.32, 0.32),
            VerticalMode.ADD,
            new Push(0.5, 0.1),
            new WtapExtra(false, 0.5, 0.1),
            new Friction(0.5556, 0.5556, 0.5556),
            new Limits(0.4, 0.0, -1.0), // verticalMin 0.0 — the 2.4.7 practice floor (see KOHI)
            new Push(1.0, 1.0),
            new Push(0.0, 0.0),
            RangeReduction.DISABLED,
            1.0,
            false,
            KnockbackDelivery.IMMEDIATE,
            KnockbackDelivery.TRACKER,
            ResistancePolicy.NONE,
            true);

    /**
     * The archived Lunar S5 values, confirmed byte-identical across two
     * independent archives: split friction (÷1.46 h, ÷1.31 v), heavy base,
     * weak sprint differential, cap below the base vertical.
     */
    public static final KnockbackProfile LUNAR = new KnockbackProfile(
            "lunar",
            "Lunar",
            "Lunar Network's archived S5 values — heavy base, high residual"
                    + " survival, weak sprint differential.",
            new Push(0.54, 0.44),
            VerticalMode.ADD,
            new Push(0.38, 0.0),
            new WtapExtra(false, 0.38, 0.0),
            new Friction(0.6849, 0.7634, 0.6849),
            new Limits(0.361735, 0.0, -1.0), // verticalMin 0.0 — the 2.4.7 practice floor (see KOHI)
            new Push(1.0, 1.0),
            new Push(0.0, 0.0),
            RangeReduction.DISABLED,
            1.0,
            false,
            KnockbackDelivery.IMMEDIATE,
            KnockbackDelivery.TRACKER,
            ResistancePolicy.NONE,
            true);

    /** The archived MineHQ values: between kohi and vanilla, 1.7.10 era model like kohi. */
    public static final KnockbackProfile MINEHQ = new KnockbackProfile(
            "minehq",
            "MineHQ",
            "MineHQ's archived HCF values — between Kohi and vanilla, 1.7.10 ledger combos.",
            new Push(0.36, 0.36),
            VerticalMode.ADD,
            new Push(0.45, 0.09),
            new WtapExtra(false, 0.45, 0.09),
            new Friction(0.5, 0.5, 0.5),
            new Limits(0.4, 0.0, -1.0), // verticalMin 0.0 — the 2.4.7 practice floor (see KOHI)
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
     * The archived NoDebuff/PotPvP values (both archives): softest base of
     * the practice set, on the 1.7 back-end Badlion ran through its NoDebuff
     * prime (ledger combos, tracker wire).
     */
    public static final KnockbackProfile BADLION = new KnockbackProfile(
            "badlion",
            "Badlion",
            "Badlion's archived NoDebuff values — soft base 0.34, strong sprint"
                    + " differential, 1.7 ledger combos.",
            new Push(0.34, 0.34),
            VerticalMode.ADD,
            new Push(0.48, 0.085),
            new WtapExtra(false, 0.48, 0.085),
            new Friction(0.5, 0.5, 0.5),
            new Limits(0.4, 0.0, -1.0), // verticalMin 0.0 — the 2.4.7 practice floor (see KOHI)
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
     * The archived VeltPvP values: friction divisor 10 → 0.1 residual wipe,
     * fixed 0.36 vertical (cap == base), zero sprint vertical — the late-era
     * "dead consistent" practice shape.
     */
    public static final KnockbackProfile VELT = new KnockbackProfile(
            "velt",
            "Velt",
            "VeltPvP's archived values — residual wipe (0.1 survival), fixed"
                    + " 0.36 vertical, full sprint horizontal.",
            new Push(0.325, 0.36),
            VerticalMode.ADD,
            new Push(0.5, 0.0),
            new WtapExtra(false, 0.5, 0.0),
            new Friction(0.1, 0.1, 0.1),
            new Limits(0.36, -3.9, -1.0),
            new Push(1.0, 1.0),
            new Push(0.0, 0.0),
            RangeReduction.DISABLED,
            1.0,
            false,
            KnockbackDelivery.TRACKER,
            KnockbackDelivery.TRACKER,
            ResistancePolicy.NONE,
            true);

    /**
     * Mental's own velt derivative, tuned by playtesting: velt's residual
     * wipe, sprint horizontal, and 0.36 vertical cap, with three changes —
     * air.horizontal 0.92 (the airborne pocket trim), base.vertical 0.365
     * (a touch above the cap, so descending hits keep more lift), and
     * air.vertical 0.98 (the airborne vertical trim).
     */
    public static final KnockbackProfile SIGNATURE = new KnockbackProfile(
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
            // Mental's own preset, and the owner's explicit ask: opt into
            // speed-conformal knockback so Speed/Slowness fights keep the
            // base-speed combo rhythm (design 2026-07-04). Archived-server
            // presets stay OFF — they are historical records.
            //
            // Exponent 0.95 (2.4.1): the owner's Speed-III feel tune, a light
            // temper of the fully-conformal 1.0. Base speed is the anchor and is
            // exponent-invariant — s = (attr/baseline)^e is 1.0 at attr == baseline
            // for ANY e, and the live base ratio carries only ~1.5e-8 of float slack
            // (below the wire quantum), so plain play still ships the era stamp
            // byte-identically. Only genuine speed moves off 1.0: a Speed III sprint
            // (walk-normalized 0.16/0.10 = 1.6) scales 1.6^0.95 ≈ 1.563 instead of
            // 1.6 — a touch less compression at the top of the window.
            new PaceScaling(PaceScaling.Mode.ATTACKER, 0.95, 0.5, 2.0));

    /**
     * Ships as legacy-1.7 values — selecting it changes nothing until the
     * owner edits the file.
     */
    public static final KnockbackProfile CUSTOM = new KnockbackProfile(
            "custom",
            "Custom",
            "Your own knockback tuning — edit profiles/custom.yml.",
            KnockbackProfile.LEGACY_17.base(),
            KnockbackProfile.LEGACY_17.verticalMode(),
            KnockbackProfile.LEGACY_17.extra(),
            KnockbackProfile.LEGACY_17.wtapExtra(),
            KnockbackProfile.LEGACY_17.friction(),
            KnockbackProfile.LEGACY_17.limits(),
            KnockbackProfile.LEGACY_17.air(),
            KnockbackProfile.LEGACY_17.add(),
            KnockbackProfile.LEGACY_17.rangeReduction(),
            KnockbackProfile.LEGACY_17.sprintFactor(),
            KnockbackProfile.LEGACY_17.combos(),
            KnockbackProfile.LEGACY_17.meleeDelivery(),
            KnockbackProfile.LEGACY_17.projectileDelivery(),
            KnockbackProfile.LEGACY_17.resistance(),
            KnockbackProfile.LEGACY_17.shieldBlockingCancels());

    /** Every bundled preset by name, the built-in default included. */
    public static final Map<String, KnockbackProfile> ALL = List.of(
                    KnockbackProfile.LEGACY_17, LEGACY_18, KOHI, MMC, LUNAR,
                    MINEHQ, BADLION, VELT, SIGNATURE, CUSTOM)
            .stream()
            .collect(Collectors.toUnmodifiableMap(KnockbackProfile::name, Function.identity()));
}
