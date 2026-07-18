package me.vexmc.mental.v5.preset;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.v5.config.EffectsPreset;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.config.settings.DeathEffectsSettings;
import me.vexmc.mental.v5.config.settings.HitFeedbackSettings;
import me.vexmc.mental.v5.manage.Management;
import org.jetbrains.annotations.NotNull;

/**
 * The ONE read surface the GUI uses for BOTH preset kinds (spec: the unified
 * preset manager). Reads are pure functions over the immutable {@link Snapshot}
 * — safe from any thread; {@link #apply} delegates to the existing {@link
 * Management} write path and inherits its main-thread / global-region
 * requirement. No new write path exists here.
 */
public final class PresetCatalog {

    private PresetCatalog() {
    }

    private static final Map<String, String> KNOCKBACK_ICONS = Map.ofEntries(
            Map.entry("legacy-1.7", "STONE_SWORD"), Map.entry("legacy-1.8", "IRON_SWORD"),
            Map.entry("kohi", "DIAMOND_SWORD"), Map.entry("minehq", "GOLDEN_SWORD"),
            Map.entry("badlion", "IRON_AXE"), Map.entry("velt", "DIAMOND_AXE"),
            Map.entry("mmc", "BOW"), Map.entry("lunar", "ENDER_EYE"),
            Map.entry("signature", "NETHER_STAR"),
            Map.entry("modern-vanilla", "NETHERITE_SWORD"),
            Map.entry("modern-uplift", "NETHERITE_AXE"),
            Map.entry("modern-combo", "TRIDENT"), Map.entry("custom", "WRITABLE_BOOK"));

    private static final Map<String, String> EFFECTS_ICONS = Map.of(
            "signature", "NETHER_STAR", "custom", "WRITABLE_BOOK");

    /**
     * Every preset name for {@code kind}, sorted. Snapshot copies its maps with
     * Map.copyOf, which drops iteration order — both legacy pickers sorted at
     * render time; the catalog sorts once so every consumer agrees.
     */
    public static @NotNull List<String> names(@NotNull PresetKind kind, @NotNull Snapshot snapshot) {
        List<String> names = switch (kind) {
            case KNOCKBACK -> new ArrayList<>(snapshot.profileNames());
            case EFFECTS -> new ArrayList<>(snapshot.effectsPresetNames());
        };
        names.sort(null);
        return List.copyOf(names);
    }

    /** The currently selected preset name for {@code kind}. */
    public static @NotNull String selected(@NotNull PresetKind kind, @NotNull Snapshot snapshot) {
        return switch (kind) {
            case KNOCKBACK -> snapshot.defaultProfile();
            case EFFECTS -> snapshot.selectedEffectsPreset();
        };
    }

    /** Whether {@code name} is one of the bundled presets for {@code kind}. */
    public static boolean isBundled(@NotNull PresetKind kind, @NotNull String name) {
        return kind.bundledNames().contains(name);
    }

    /** One preset tile's full render info; degrades to a name-only info for an unloaded name. */
    public static @NotNull PresetInfo info(
            @NotNull PresetKind kind, @NotNull String name, @NotNull Snapshot snapshot) {
        boolean active = name.equals(selected(kind, snapshot));
        boolean bundled = isBundled(kind, name);
        switch (kind) {
            case KNOCKBACK -> {
                KnockbackProfile profile = snapshot.profile(name);
                if (profile == null) {
                    return degraded(kind, name, bundled, active);
                }
                return new PresetInfo(kind, name, profile.displayName(), profile.description(),
                        iconFor(kind, name), true, bundled, active, profile.modern().enabled(),
                        profile.modern().enabled() ? modernPreview(profile) : legacyPreview(profile));
            }
            case EFFECTS -> {
                EffectsPreset preset = snapshot.effectsPreset(name);
                if (preset == null) {
                    return degraded(kind, name, bundled, active);
                }
                return new PresetInfo(kind, name, preset.displayName(), preset.description(),
                        iconFor(kind, name), true, bundled, active, false, effectsPreview(preset));
            }
        }
        throw new AssertionError(kind);
    }

    /** Every preset for {@code kind}, in {@link #names} order — the picker's one-call render read. */
    public static @NotNull List<PresetInfo> infos(@NotNull PresetKind kind, @NotNull Snapshot snapshot) {
        List<PresetInfo> infos = new ArrayList<>();
        for (String name : names(kind, snapshot)) {
            infos.add(info(kind, name, snapshot));
        }
        return List.copyOf(infos);
    }

    /**
     * Applies {@code name} as the server-wide selection for {@code kind}. Pure
     * delegation — Management is the single write seam the tester
     * (ProfileSuite/FeedbackSuite) and the public API both pin; the catalog must
     * never grow a second path to the overlay.
     */
    public static boolean apply(
            @NotNull PresetKind kind, @NotNull String name, @NotNull Management management) {
        return switch (kind) {
            case KNOCKBACK -> management.setGlobalProfile(name);
            case EFFECTS -> management.setEffectsPreset(name);
        };
    }

    /** The themed icon for a preset name — "PAPER" for anything unmapped. */
    private static String iconFor(PresetKind kind, String name) {
        Map<String, String> icons = kind == PresetKind.KNOCKBACK ? KNOCKBACK_ICONS : EFFECTS_ICONS;
        return icons.getOrDefault(name, "PAPER");
    }

    private static PresetInfo degraded(PresetKind kind, String name, boolean bundled, boolean active) {
        return new PresetInfo(kind, name, name, "", "PAPER", false, bundled, active, false, List.of());
    }

    private static List<PresetInfo.PreviewLine> legacyPreview(KnockbackProfile profile) {
        List<PresetInfo.PreviewLine> lines = new ArrayList<>();
        lines.add(new PresetInfo.PreviewLine("Base h/v",
                round(profile.base().horizontal()) + " / " + round(profile.base().vertical())));
        lines.add(new PresetInfo.PreviewLine("Vertical",
                profile.verticalMode().name().toLowerCase(Locale.ROOT)));
        lines.add(new PresetInfo.PreviewLine("Delivery",
                profile.meleeDelivery().name().toLowerCase(Locale.ROOT).replace('_', '-')));
        lines.add(new PresetInfo.PreviewLine("Combos", profile.combos() ? "yes" : "no"));
        lines.add(new PresetInfo.PreviewLine("Sprint x", round(profile.sprintFactor())));
        lines.add(new PresetInfo.PreviewLine("Resistance",
                profile.resistance().name().toLowerCase(Locale.ROOT)));
        if (profile.paceScaling().active()) {
            lines.add(new PresetInfo.PreviewLine("Pace scale", "attacker x"
                    + round(profile.paceScaling().min()) + "–" + round(profile.paceScaling().max())
                    + " ^" + round(profile.paceScaling().exponent())));
        }
        return lines;
    }

    private static List<PresetInfo.PreviewLine> modernPreview(KnockbackProfile profile) {
        var modern = profile.modern();
        List<PresetInfo.PreviewLine> lines = new ArrayList<>();
        lines.add(new PresetInfo.PreviewLine("Base", round(modern.baseStrength())));
        lines.add(new PresetInfo.PreviewLine("Sprint +", round(modern.sprintBonus())));
        lines.add(new PresetInfo.PreviewLine("Enchant +", round(modern.enchantBonus())));
        lines.add(new PresetInfo.PreviewLine("Downward",
                modern.downwardKnockback() ? "yes (mid-air slam)" : "no (uplift)"));
        lines.add(new PresetInfo.PreviewLine("Combos", profile.combos() ? "yes" : "no"));
        lines.add(new PresetInfo.PreviewLine("Delivery",
                profile.meleeDelivery().name().toLowerCase(Locale.ROOT).replace('_', '-')));
        return lines;
    }

    private static List<PresetInfo.PreviewLine> effectsPreview(EffectsPreset preset) {
        HitFeedbackSettings hit = preset.hitFeedback();
        List<PresetInfo.PreviewLine> lines = new ArrayList<>();
        lines.add(new PresetInfo.PreviewLine("Hit sounds", hit.vanillaTune()
                ? "vanilla hurt (era jitter)"
                : hit.sounds().size() + " layered"));
        lines.add(new PresetInfo.PreviewLine("Hit particles", hit.particles().isEmpty()
                ? "none" : String.valueOf(hit.particles().size())));
        lines.add(new PresetInfo.PreviewLine("Low-HP layer", hit.lowHealthSounds().isEmpty()
                ? "none"
                : "below " + round(hit.lowHealthThresholdPercent()) + "% of max health"));
        lines.add(new PresetInfo.PreviewLine("Indicator", preset.damageIndicators().text()));
        DeathEffectsSettings death = preset.deathEffects();
        boolean deathNothing = !death.lightning() && death.sounds().isEmpty()
                && death.particles().isEmpty() && death.fireworkColors().isEmpty();
        if (deathNothing) {
            lines.add(new PresetInfo.PreviewLine("Death", "nothing (vanilla)"));
        } else {
            lines.add(new PresetInfo.PreviewLine("Death", (death.lightning() ? "lightning" : "no bolt")
                    + " · " + death.sounds().size() + " sounds"
                    + (death.fireworkColors().isEmpty()
                            ? "" : " · " + death.fireworkColors().size() + "-color blast")));
        }
        return lines;
    }

    private static String round(double value) {
        return String.valueOf(Math.round(value * 1000.0) / 1000.0);
    }
}
