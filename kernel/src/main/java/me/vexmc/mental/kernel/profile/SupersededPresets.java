package me.vexmc.mental.kernel.profile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.vexmc.mental.kernel.profile.KnockbackProfile.Friction;
import me.vexmc.mental.kernel.profile.KnockbackProfile.Limits;
import me.vexmc.mental.kernel.profile.KnockbackProfile.Push;
import me.vexmc.mental.kernel.profile.KnockbackProfile.RangeReduction;
import me.vexmc.mental.kernel.profile.KnockbackProfile.WtapExtra;

/**
 * Every bundled-preset revision that later research corrected, exactly as it
 * shipped. A profile file on disk that is byte-identical (after newline
 * normalization) to one of these was never tuned by the owner — it is the old
 * bundle verbatim, and {@code ConfigStore} upgrades it in place to the
 * corrected preset ({@link #isSupersededBundleText}, the RAW-BYTE contract).
 * A file that differs in ANYTHING — a value, a comment, whitespace — is an
 * owner edit and is never touched.
 *
 * <p>Byte identity replaced value matching in 2.4.9: value matching could not
 * distinguish an unedited old bundle from an owner edit that happened to land on
 * old values, and the bundled files' own comments invite exactly such an edit
 * ("Restore -3.9 to unfloor"). The value-based API ({@link #of},
 * {@link #isSupersededVerbatim}) is retained additively for the kernel's era
 * pins, but {@code ConfigStore} no longer calls it.</p>
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
 *
 * <p>The modern-formula round appends the commented {@code formula:}/{@code
 * modern:} schema block to custom.yml (its parsed VALUES are unchanged — the
 * block ships {@code formula: legacy} at the modern OFF defaults). Only the
 * file's TEXT moved, so the custom hash set gains the {@code @2.4.8}
 * pre-modern-block form (byte-only — no value revision, since the parsed value
 * is identical to the current bundle) so an unedited custom.yml upgrades in
 * place to gain the documentation.</p>
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

    /**
     * SHA-256 (lowercase hex) of every superseded bundled revision's exact
     * historical text, newline-normalized (CRLF/CR → LF), keyed by preset. Since
     * 2.4.9 {@code ConfigStore} matches the pristine upgrade on these RAW BYTES,
     * not on parsed values (see the class javadoc). Storing hashes rather than the
     * full texts keeps the kernel pure-JDK and small; the texts themselves are
     * pinned verbatim as core test resources under {@code superseded-bundles/},
     * with a test that recomputes every hash and asserts none collides with a
     * current bundle (the self-upgrade-loop guard).
     *
     * <p>Pre-1.4.0 forms are hashed WITH the 1.4.0 {@code delivery} block already
     * inserted, because {@code ConfigStore.ensureDeliverySection} patches the file
     * before the upgrade check reads it (custom is excluded from that patch, so its
     * 1.3.x form is hashed raw). {@code kohi@1.5.0}/{@code lunar@1.5.0} are the
     * untagged 1.5.0-era dev shape, superseded by the archived-values calibration
     * before v1.8.0 shipped — never released, so inert, but kept for completeness.</p>
     */
    private static final Map<String, Set<String>> BUNDLE_SHA256_BY_PRESET = Map.of(
            "kohi", Set.of(
                    "a4e64365023c076d5b5cb0a40153ff3cc2b9565619a2be45f0698da08fcbde64", // kohi@1.3.x (delivery-patched)
                    "e39436b24955f86b87369b6a891a792802b51e09546917c355109343ebb69a82", // kohi@1.4.0
                    "f0dda97b88cc965fa70deb3ada27dbd9e098e1b7eead8d336337de3688d810d9", // kohi@1.5.0 (dev-only, inert)
                    "8a46f1d452f7f181bbdd6e6195aefe355dfac1e7afc5c0f6a95ea46f22e2271c"), // kohi@1.8.0 → 2.4.6
            "mmc", Set.of(
                    "dcdb742a16a898d75c99cd805e438cbd206f77ad670a371d4316883ceffe14c1", // mmc@1.3.x (delivery-patched)
                    "3c6542457ab6f58c10363682c0479b6b0f66be58bf28df3376faa11984199085", // mmc@1.4.0 (== 1.5.0 bytes)
                    "dc94241a6b7c776e7f00356b21417db00959dffdce8eed5473c8f7f494a2d4db"), // mmc@1.8.0 → 2.4.6
            "lunar", Set.of(
                    "3eb5a98f0cd4e4ef544d26805fce69f040b46d4de628af9dfe2f26af951eea8d", // lunar@1.3.x (delivery-patched)
                    "c093c91694e4cbd6b6dcef1605c7d1ca0f574ed1c9dfaa0708bede55b805e3ce", // lunar@1.4.0
                    "08c555910b3922e49f5a5572438b4c654761f5fb0c4a34600008f45f135dbb01", // lunar@1.5.0 (dev-only, inert)
                    "77bfe61b057a1f382b99a06431c8f37c338c8b2f05a036562e43c2c1fc58e230"), // lunar@1.8.0 → 2.4.6
            "minehq", Set.of(
                    "b00795419682ae0a0399233c7405a0c71f00827e2c72af6ee2833ac4b3df07a3"), // minehq@1.8.0 → 2.4.6
            "badlion", Set.of(
                    "ed4f7249455388a17a1e615d724efb037da77cb9ebd50eaec6830a8f81708477"), // badlion@1.8.0 → 2.4.6
            "signature", Set.of(
                    "aebaf73472a5b66a79214bd516926a8cfea9ad67c37c2d24135a48b1f57d308e", // signature@2.2.0
                    "f87480dc0754f3e28045e050ab5f857ac5afb64f583286edf67844a0d307217e", // signature@2.2.1 → 2.3.2
                    "fd22b141dda57fa1c37e3ead86f147bd371aa48966be69004ce2294e81f1837f"), // signature@2.4.0
            "legacy-1.7", Set.of(
                    "06b2b21b83435366c8629bd053b68f4a70af6044a6c457cc79abd232b335e944", // legacy-1.7@1.3.x (delivery-patched)
                    "133d912ebdc09e2def3ba0a2009b2b69f618f09e81398886f1350cd02b9b61df", // legacy-1.7@1.4.0
                    "c5febe6f01d1b0811791e66d4131fffcc19172e7c86b0a294b3ab2ae1cc38ffe", // legacy-1.7@1.5.0 → 2.4.6
                    "fb6f7d91d61a8db3820b0ded85beb563166ee11da4e6db665b188920f07bbe08"), // legacy-1.7@2.4.7
            "legacy-1.8", Set.of(
                    "d5210235b0e63987657e7656108087751a2583bbdbd64785b4ea24802e16c80b", // legacy-1.8@1.3.x (delivery-patched)
                    "7e9aa22ef48e8ec1b50b74c2b31af38f1687c9bddc629327fcc21b86bcdf0ad7", // legacy-1.8@1.4.0
                    "0d3b083c1292d8602b33ba80d1b808548bf3e3f0c76a1c473e14e5db454ee573", // legacy-1.8@1.5.0 → 2.4.6
                    "e5c1ba56beaf74f10dcf4fd9abfed2c2376e05f9f7ffc6bc9ebc201224bf3787"), // legacy-1.8@2.4.7
            "custom", Set.of(
                    "7f367ca455f7126ff7203f25209ae29757c932b06f6bde329e6ecca0926828f3", // custom@1.3.x (raw — patcher excludes custom)
                    "50fcd1dae0b1934566ac909d90971c915cb9bb9f06e335f2a60081f0794dc2f1", // custom@1.4.0
                    "5df319ae3c706bb659e2b2c3c5c3b6e00837622bbf93dfb04b129bc708e0f7cb", // custom@1.5.0 → 2.2.2
                    "fd3ab8a15f5e78b06def73ab5dcce1b2c5babb5b84e798bde50378c3f0716b56", // custom@2.4.0 → 2.4.7
                    "12e5fcb54b93d7095eaa9205e7ddba07fb284c3187bf8da552869839f9f03516")); // custom@2.4.8 (pre-modern-block)

    /** Whether {@code fileText} is byte-identical (newline-normalized) to a superseded bundled revision of {@code preset}. */
    public static boolean isSupersededBundleText(String preset, String fileText) {
        Set<String> hashes = BUNDLE_SHA256_BY_PRESET.get(preset);
        if (hashes == null || fileText == null) {
            return false;
        }
        String normalized = fileText.replace("\r\n", "\n").replace('\r', '\n');
        return hashes.contains(sha256Hex(normalized));
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                   .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
