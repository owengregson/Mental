package me.vexmc.mental.v5.config;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import me.vexmc.mental.v5.config.settings.AnticheatSettings;
import me.vexmc.mental.v5.config.settings.ComboSettings;
import me.vexmc.mental.v5.config.settings.CompensationSettings;
import me.vexmc.mental.v5.config.settings.CraftingSettings;
import me.vexmc.mental.v5.config.settings.DamageIndicatorsSettings;
import me.vexmc.mental.v5.config.settings.DeathEffectsSettings;
import me.vexmc.mental.v5.config.settings.DebugSettings;
import me.vexmc.mental.v5.config.settings.DropProtectionSettings;
import me.vexmc.mental.v5.config.settings.FastPotsSettings;
import me.vexmc.mental.v5.config.settings.HitFeedbackSettings;
import me.vexmc.mental.v5.config.settings.FishingKnockbackSettings;
import me.vexmc.mental.v5.config.settings.HitRegSettings;
import me.vexmc.mental.v5.config.settings.NoSettings;
import me.vexmc.mental.v5.config.settings.OffhandSettings;
import me.vexmc.mental.v5.config.settings.PotFillSettings;
import me.vexmc.mental.v5.config.settings.ProjectileKnockbackSettings;
import me.vexmc.mental.v5.config.settings.ReachHandicapSettings;
import me.vexmc.mental.kernel.math.TargetMode;
import me.vexmc.mental.v5.feature.Feature;
import org.bukkit.Material;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Parses the post-overlay YAML (config.yml + knockback.yml + hit-registration.yml
 * + latency-compensation.yml + combo.yml + pots.yml + loadout.yml + effects.yml
 * + effects/presets/*.yml + profiles/*.yml) into one immutable {@link Snapshot}, with the
 * warn-and-fallback issue list. This is the retired {@code MentalConfig.reload}
 * re-expressed over the descriptor registry: every module toggle keys off
 * {@link Feature#yamlKey()}, every settings record keys off
 * {@link Feature#settingsKey()}, and {@code parse(empty) == full defaults}.
 * The per-record parse logic is ported field-for-field from the retired
 * {@code config.*Settings} records (whose parse methods it absorbs).
 *
 * <p>The per-module settings sections that lived in config.yml before 2.5.2
 * (the {@link ConfigStore#SPLIT_FILE_SECTIONS} map) resolve through
 * {@link #movedSection}: the split file's section wins when present; an
 * old-location config.yml section is honoured verbatim with one loud issue
 * line per parse (the 2.4.4 reach-handicap promotion contract) — never a
 * silent drop.</p>
 */
public final class SnapshotParser {

    /** The parsed snapshot plus every warn-and-fallback issue encountered. */
    public record Result(Snapshot snapshot, List<String> issues) {}

    private SnapshotParser() {}

    /**
     * The six per-module sections that moved out of config.yml in 2.5.2, each
     * resolved exactly once per parse (so the moved-section notice is emitted
     * once, however many parsers consult the reader). The three FEEDBACK
     * sections left this record with the 2.5.3 preset library — a lingering
     * config.yml copy is named by {@link #reportRetiredEffectsSections}.
     */
    private record MovedSections(
            ConfigReader comboHold,
            ConfigReader reachHandicap,
            ConfigReader potFill,
            ConfigReader fastPots,
            ConfigReader offhand,
            ConfigReader crafting) {}

    public static Result parse(ConfigStore.Sources sources) {
        Configuration main = sources.main();
        Configuration knockback = sources.knockback();
        ConfigIssues issues = new ConfigIssues();
        ConfigReader modules = new ConfigReader(
                main.getConfigurationSection("modules"), "config.yml: modules", issues);

        MovedSections moved = new MovedSections(
                movedSection(sources.combo(), ConfigStore.COMBO_FILE, main, "combo-hold", issues),
                movedSection(sources.combo(), ConfigStore.COMBO_FILE, main, "combo-reach-handicap", issues),
                movedSection(sources.pots(), ConfigStore.POTS_FILE, main, "pot-fill", issues),
                movedSection(sources.pots(), ConfigStore.POTS_FILE, main, "fast-pots", issues),
                movedSection(sources.loadout(), ConfigStore.LOADOUT_FILE, main, "disable-offhand", issues),
                movedSection(sources.loadout(), ConfigStore.LOADOUT_FILE, main, "disable-crafting", issues));
        reportRetiredEffectsSections(main, issues);

        // The Combat Effects preset library (2.5.3): parse every preset file,
        // resolve the effects.yml selection (overlay ?? file ?? default — the
        // overlay was applied onto the effects root before this parse), and
        // feed the selected preset's three sections into the FEEDBACK settings
        // records below. A bad name already warned and fell back to vanilla.
        EffectsPresetParser.Library effectsLibrary = EffectsPresetParser.parseSection(
                reader(sources.effects(), "effects", "effects.yml", issues),
                sources.effectsPresets(), issues);
        EffectsPreset effects = effectsLibrary.effective();

        // The combo reach handicap was promoted to its own module in 2.4.4; its
        // enablement carries a loud legacy-key migration, so it is resolved once here
        // and the loop below reuses it (COMBO_HOLD likewise, to avoid a double read).
        boolean comboHoldOn =
                modules.flag(Feature.COMBO_HOLD.yamlKey(), Feature.COMBO_HOLD.defaultEnabled());
        boolean reachHandicapOn = resolveReachHandicapEnabled(modules, moved.comboHold(), issues);

        Snapshot.Builder builder = new Snapshot.Builder();
        for (Feature feature : Feature.values()) {
            boolean on;
            if (feature.infrastructure()) {
                on = feature.defaultEnabled();
            } else if (feature == Feature.COMBO_HOLD) {
                on = comboHoldOn;
            } else if (feature == Feature.COMBO_REACH_HANDICAP) {
                on = reachHandicapOn;
            } else {
                on = modules.flag(feature.yamlKey(), feature.defaultEnabled());
            }
            builder.enable(feature, on);
            builder.put(feature.settingsKey(), settingsFor(feature, sources, moved, effects, issues));
        }
        builder.effects(effectsLibrary);
        // The reach handicap no longer depends on combo-hold: since the 2.4.5
        // detection/servo split it drives combo DETECTION itself (either keeper does),
        // so it engages on its own combos even with the pocket servo off. No
        // dependency warning is emitted; the two combo modules toggle independently.

        builder.profiles(ProfileParser.parseSection(
                reader(knockback, "knockback", "knockback.yml", issues), sources.profiles(), issues));
        builder.anticheat(parseAnticheat(reader(main, "anticheat", "config.yml", issues)));
        builder.debug(parseDebug(reader(main, "debug", "config.yml", issues)));
        // bStats metrics toggle (spec §13). Parse-with-default: an absent
        // `metrics` section (or key) reads true silently — the frozen bundled
        // config need not carry it. Warn-and-fallback on a non-boolean value.
        builder.metricsEnabled(reader(main, "metrics", "config.yml", issues).flag("enabled", true));

        return new Result(builder.build(), issues.all());
    }

    /**
     * Resolves one section that moved out of config.yml in 2.5.2 into its
     * per-concern split file. The split file's section wins when present; an
     * old-location config.yml section is HONOURED verbatim with one loud issue
     * line per parse (the 2.4.4 reach-handicap promotion contract — an in-place
     * upgrade keeps working and is told exactly what to move); when both carry
     * the section the split file wins and the shadowed config.yml section is
     * named loudly, never dropped in silence (mandate B10).
     */
    private static ConfigReader movedSection(
            Configuration splitRoot, String splitFile, Configuration main,
            String section, ConfigIssues issues) {
        boolean inSplit = splitRoot.getConfigurationSection(section) != null;
        boolean inMain = main.getConfigurationSection(section) != null;
        if (inMain && inSplit) {
            issues.add("config.yml: the " + section + " section moved to " + splitFile
                    + " in 2.5.2 and BOTH carry it — " + splitFile + " wins and the config.yml"
                    + " section is ignored; delete it from config.yml");
        } else if (inMain) {
            issues.add("config.yml: the " + section + " section moved to " + splitFile
                    + " in 2.5.2 — the config.yml section is honoured for now; move it: delete"
                    + " it from config.yml and the bundled " + splitFile + " (with the full"
                    + " documentation) is extracted on the next boot, ready to carry your values");
            return new ConfigReader(main.getConfigurationSection(section),
                    "config.yml: " + section, issues);
        }
        return new ConfigReader(splitRoot.getConfigurationSection(section),
                splitFile + ": " + section, issues);
    }

    private static Object settingsFor(
            Feature feature,
            ConfigStore.Sources sources,
            MovedSections moved,
            EffectsPreset effects,
            ConfigIssues issues) {
        return switch (feature) {
            case HIT_REGISTRATION ->
                    parseHitReg(reader(sources.hitReg(), "hit-registration", "hit-registration.yml", issues));
            case LATENCY_COMPENSATION ->
                    parseCompensation(reader(sources.latency(), "latency-compensation",
                            "latency-compensation.yml", issues));
            case FISHING_KNOCKBACK ->
                    parseFishing(reader(sources.knockback(), "fishing-knockback", "knockback.yml", issues));
            case PROJECTILE_KNOCKBACK ->
                    parseProjectile(reader(sources.knockback(), "projectile-knockback", "knockback.yml", issues));
            case CRAFTING -> parseCrafting(moved.crafting());
            case OFFHAND -> parseOffhand(moved.offhand());
            case COMBO_HOLD -> parseCombo(moved.comboHold());
            case COMBO_REACH_HANDICAP -> parseReachHandicap(
                    moved.reachHandicap(), moved.comboHold().sub("reach-handicap"));
            case POT_FILL -> parsePotFill(moved.potFill());
            case FAST_POTS -> parseFastPots(moved.fastPots());
            case DROP_PROTECTION -> parseDropProtection(reader(
                    sources.dropProtection(), "drop-protection", ConfigStore.DROP_PROTECTION_FILE, issues));
            // The FEEDBACK family reads the selected Combat Effects preset, then
            // layers any in-GUI per-field overrides (the machine overlay) on top —
            // effective = overlay ?? preset ?? default. The overrides live under
            // effects.<module>.<field> on the effects.yml root the overlay merges
            // into (the preset files are never re-serialized).
            case HIT_FEEDBACK -> applyHitOverrides(
                    effects.hitFeedback(), effectsOverrides(sources, "hit", issues));
            case DAMAGE_INDICATORS -> applyIndicatorOverrides(
                    effects.damageIndicators(), effectsOverrides(sources, "indicators", issues));
            case DEATH_EFFECTS -> applyDeathOverrides(
                    effects.deathEffects(), effectsOverrides(sources, "death", issues));
            default -> NoSettings.DEFAULTS;
        };
    }

    /**
     * A reader over the in-GUI override subsection for one effects module
     * ({@code effects.<module>} on the effects.yml root the overlay merges into).
     * A null section (no override set) makes every field read fall back to the
     * preset value, so an un-edited install is byte-identical to the preset.
     */
    private static ConfigReader effectsOverrides(
            ConfigStore.Sources sources, String module, ConfigIssues issues) {
        ConfigurationSection section = sources.effects().getConfigurationSection("effects." + module);
        return new ConfigReader(section, "effects." + module, issues);
    }

    /** Layers the GUI-editable death fields (kill title + lightning) over the preset. */
    private static DeathEffectsSettings applyDeathOverrides(DeathEffectsSettings preset, ConfigReader reader) {
        if (reader.section() == null) {
            return preset;
        }
        DeathEffectsSettings.KillTitle kt = preset.killTitle();
        DeathEffectsSettings.KillTitle title = new DeathEffectsSettings.KillTitle(
                reader.text("kill-title", kt.title()),
                reader.text("kill-subtitle", kt.subtitle()),
                reader.intClamped("title-fade-in", kt.fadeIn(), 0, 200),
                reader.intClamped("title-stay", kt.stay(), 0, 400),
                reader.intClamped("title-fade-out", kt.fadeOut(), 0, 200));
        return new DeathEffectsSettings(
                reader.flag("lightning", preset.lightning()),
                preset.sounds(), preset.particles(), preset.fireworkColors(), title);
    }

    /** Layers the GUI-editable indicator label templates over the preset. */
    private static DamageIndicatorsSettings applyIndicatorOverrides(
            DamageIndicatorsSettings preset, ConfigReader reader) {
        if (reader.section() == null) {
            return preset;
        }
        return new DamageIndicatorsSettings(
                preset.lifetimeTicks(), preset.ringRadius(), preset.heightJitter(),
                preset.launchVertical(), preset.launchOutward(), preset.gravity(), preset.drag(),
                reader.text("text", preset.text()),
                reader.text("crit-text", preset.critText()),
                preset.critThresholdHearts(),
                reader.text("heal-text", preset.healText()));
    }

    /** Layers the GUI-editable hit-feedback low-health threshold over the preset. */
    private static HitFeedbackSettings applyHitOverrides(HitFeedbackSettings preset, ConfigReader reader) {
        if (reader.section() == null) {
            return preset;
        }
        return new HitFeedbackSettings(
                preset.sounds(), preset.particles(), preset.lowHealthSounds(),
                reader.numberClamped("low-health-threshold-percent",
                        preset.lowHealthThresholdPercent(), 0.0, 100.0));
    }

    /**
     * The FEEDBACK sections died with the 2.5.3 preset library — their schema
     * (the per-module {@code preset:} enum) no longer exists, so a lingering
     * config.yml section can not be honoured. It is never dropped in silence
     * either (mandate B10): one loud line per section, per parse, naming where
     * the values went (the 3 → 4 migration imported them into
     * {@code effects/presets/custom.yml}) and the way out.
     */
    private static void reportRetiredEffectsSections(Configuration main, ConfigIssues issues) {
        for (String section : ConfigStore.RETIRED_EFFECTS_SECTIONS) {
            if (main.getConfigurationSection(section) != null) {
                issues.add("config.yml: the " + section + " section was retired in 2.5.3 —"
                        + " Combat Effects are preset files now (effects/presets/, selected in"
                        + " effects.yml; the migration imported your old values into"
                        + " effects/presets/custom.yml). The section is ignored; delete it"
                        + " from config.yml");
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Per-record parsers (ported field-for-field from config.*Settings)  */
    /* ------------------------------------------------------------------ */

    private static HitRegSettings parseHitReg(ConfigReader reader) {
        HitRegSettings d = HitRegSettings.DEFAULTS;
        ConfigReader fastPath = reader.sub("fast-path");
        return new HitRegSettings(
                reader.intAtLeast("max-cps", d.maxCps(), 0),
                fastPath.flag("enabled", d.fastPath()),
                fastPath.flag("pre-send-feedback", d.preSendFeedback()),
                parseFeedbackInterval(fastPath),
                fastPath.flag("bundle-feedback", d.bundleFeedback()),
                fastPath.flag("simulate-crits", d.simulateCrits()),
                fastPath.flag("legacy-tool-damage", d.legacyToolDamage()),
                parseReachValidation(reader.sub("reach-validation")));
    }

    private static long parseFeedbackInterval(ConfigReader fastPath) {
        if (fastPath.section() == null || !fastPath.section().isSet("feedback-min-interval-ms")) {
            return HitRegSettings.DEFAULTS.feedbackMinIntervalMillis();
        }
        if ("auto".equalsIgnoreCase(String.valueOf(fastPath.section().get("feedback-min-interval-ms")))) {
            return HitRegSettings.FEEDBACK_INTERVAL_AUTO;
        }
        return fastPath.ticksAtLeast("feedback-min-interval-ms", 500L, 0);
    }

    private static HitRegSettings.ReachValidation parseReachValidation(ConfigReader reader) {
        HitRegSettings.ReachValidation d = HitRegSettings.ReachValidation.DEFAULTS;
        return new HitRegSettings.ReachValidation(
                reader.flag("enabled", d.enabled()),
                reader.numberAtLeast("max-reach", d.maxReach(), 0.5),
                reader.numberAtLeast("leniency", d.leniency(), 0),
                reader.intAtLeast("interpolation-offset-ms", d.interpolationOffsetMillis(), 0),
                reader.intAtLeast("rewind-cap-ms", d.rewindCapMillis(), 0));
    }

    private static CompensationSettings parseCompensation(ConfigReader reader) {
        CompensationSettings d = CompensationSettings.DEFAULTS;
        // The RAW configured transport is stored here; the parser stays version-blind.
        // Its reconciliation to the effective wire transport (below 1.17 → TRANSACTION,
        // at/above → PING; KEEPALIVE always retires) happens at the one boot seam that
        // knows the server version — MentalPluginV5.parseSnapshot via
        // ProbeStrategy.resolveEffective, where the loud info/warn line is emitted.
        ProbeStrategy probeStrategy = reader.oneOf("probe-strategy", d.probeStrategy(), ProbeStrategy.class);
        // ping-offset-ms was retired in 2.4.9 — it was never applied (the measured round
        // trip is used as-is), so honour a lingering key with a one-line notice instead of
        // silently dropping a value the operator may believe is doing something.
        if (reader.section() != null && reader.section().isSet("ping-offset-ms")) {
            reader.issues().add("latency-compensation.ping-offset-ms: retired — it was never"
                    + " applied (the measured round trip is used as-is); delete the line");
        }
        return new CompensationSettings(
                probeStrategy,
                reader.intAtLeast("spike-threshold-ms", d.spikeThresholdMillis(), 1),
                reader.ticksAtLeast("probe-interval-ticks", d.probeIntervalTicks(), 1),
                reader.ticksAtLeast("combat-timeout-ticks", d.combatTimeoutTicks(), 1),
                reader.flag("off-ground-sync", d.offGroundSync()));
    }

    private static FishingKnockbackSettings parseFishing(ConfigReader reader) {
        FishingKnockbackSettings d = FishingKnockbackSettings.DEFAULTS;
        return new FishingKnockbackSettings(
                reader.numberAtLeast("damage", d.damage(), 0),
                reader.oneOf("reel-in", d.reelIn(), ReelInPolicy.class),
                reader.flag("knockback-non-player-entities", d.knockbackNonPlayerEntities()));
    }

    private static ProjectileKnockbackSettings parseProjectile(ConfigReader reader) {
        ProjectileKnockbackSettings d = ProjectileKnockbackSettings.DEFAULTS;
        ConfigReader damage = reader.sub("damage");
        return new ProjectileKnockbackSettings(
                reader.flag("arrows", d.arrows()),
                damage.numberAtLeast("snowball", d.snowballDamage(), 0),
                damage.numberAtLeast("egg", d.eggDamage(), 0),
                damage.numberAtLeast("ender-pearl", d.enderPearlDamage(), 0));
    }

    private static CraftingSettings parseCrafting(ConfigReader reader) {
        List<String> names = reader.stringList("items", List.of("SHIELD"));
        Set<Material> blocked = names.stream()
                .map(name -> matchMaterial(name, "disable-crafting.items", reader))
                .filter(material -> material != null)
                .collect(Collectors.toUnmodifiableSet());
        return new CraftingSettings(blocked);
    }

    private static ComboSettings parseCombo(ConfigReader reader) {
        ComboSettings d = ComboSettings.DEFAULTS;
        double minFactor = reader.numberAtLeast("min-factor", d.minFactor(), 0.0);
        double maxFactor = reader.numberAtLeast("max-factor", d.maxFactor(), 0.0);
        // Cross-field sanity: a transposed pair (min > max) turns the servo's
        // Math.max(min, Math.min(max, blended)) clamp into a constant min-factor
        // amplifier with no per-field parse noise — a non-era knock on every combo
        // hit. Warn and fall BOTH back to the defaults (the warn-and-fallback contract).
        if (minFactor > maxFactor) {
            reader.issues().warn(reader.prefix() + ".min-factor/max-factor",
                    "min-factor " + minFactor + " exceeds max-factor " + maxFactor
                            + " (the clamp would pin every combo hit to min-factor)",
                    "defaults " + d.minFactor() + "/" + d.maxFactor());
            minFactor = d.minFactor();
            maxFactor = d.maxFactor();
        }
        // A pre-2.4.5 config's `hit-cap` clamped the removed exposure-budget dynamic
        // target — it is no longer a knob. Note it once so a tuned value is never
        // silently dropped, then ignore it (the answer-denial boundary is bounded by
        // victim-reach / attacker-reach / target-floor instead).
        if (reader.section() != null && reader.section().isSet("hit-cap")) {
            reader.issues().warn(reader.prefix() + ".hit-cap",
                    "hit-cap was removed in 2.4.5 with the exposure-budget dynamic target",
                    "ignored — the answer-denial boundary uses victim-reach/attacker-reach/target-floor");
        }
        return new ComboSettings(
                reader.intAtLeast("min-hits", d.minHits(), 1),
                reader.intAtLeast("max-gap-ticks", d.maxGapTicks(), 1),
                reader.intAtLeast("grounded-run-ticks", d.groundedRunTicks(), 1),
                reader.numberAtLeast("blowout-blocks", d.blowoutBlocks(), 0.0),
                // `target` is the STATIC fallback separation — the old anchor key,
                // reused so an upgraded config's tuned value carries over.
                reader.numberAtLeast("target", d.staticTarget(), 0.5),
                reader.numberAtLeast("gain", d.gain(), 0.0),
                minFactor,
                maxFactor,
                reader.intAtLeast("window-ticks", d.windowTicks(), 1),
                parseTargetMode(reader, d.targetMode()),
                reader.numberInRange("victim-reach", d.victimReach(), 0.5, 6.0),
                reader.numberInRange("attacker-reach", d.attackerReach(), 0.5, 6.0),
                reader.numberAtLeast("deny-margin", d.denyMargin(), 0.0),
                reader.numberAtLeast("jitter-margin", d.jitterMargin(), 0.0),
                reader.numberAtLeast("target-floor", d.targetFloor(), 0.5));
    }

    /**
     * The combo target-mode with the 2.4.5 migration (answer-denial redesign). The
     * pre-2.4.5 {@code anchor} mode maps to {@link TargetMode#STATIC} (a fixed target
     * separation) and the exposure-budget {@code dynamic} mode maps to
     * {@link TargetMode#BOUNDARY} (the geometric answer-denial target), both with a
     * loud migration note; a new {@code boundary}/{@code static} value parses
     * straight; anything else warns-and-falls-back to the default. An unset key keeps
     * the default (BOUNDARY), so {@code parse(empty)} stays the era-exact no-op.
     */
    private static TargetMode parseTargetMode(ConfigReader reader, TargetMode def) {
        ConfigurationSection section = reader.section();
        if (section == null || !section.isSet("target-mode")) {
            return def;
        }
        String raw = String.valueOf(section.get("target-mode")).trim().toLowerCase(Locale.ROOT);
        if ("anchor".equals(raw)) {
            reader.issues().warn(reader.prefix() + ".target-mode",
                    "the 'anchor' mode became 'static' in 2.4.5 (a fixed target separation)",
                    "migrated to STATIC");
            return TargetMode.STATIC;
        }
        if ("dynamic".equals(raw)) {
            reader.issues().warn(reader.prefix() + ".target-mode",
                    "the exposure-budget 'dynamic' target became the geometric 'boundary' target in 2.4.5",
                    "migrated to BOUNDARY");
            return TargetMode.BOUNDARY;
        }
        return reader.oneOf("target-mode", def, TargetMode.class);
    }

    /**
     * The promoted combo reach-handicap scale (2.4.4). Reads the top-level
     * {@code combo-reach-handicap.reach-scale}; on an in-place upgrade whose config
     * predates the promotion it falls back to the legacy nested
     * {@code combo-hold.reach-handicap.reach-scale} for one release window, so a
     * tuned value is never silently lost. A handicap only ever shortens reach, so
     * the scale is confined to {@code [0.5, 1.0]}; anything outside warns and 0.87
     * stands.
     */
    private static ReachHandicapSettings parseReachHandicap(ConfigReader reader, ConfigReader legacy) {
        ReachHandicapSettings d = ReachHandicapSettings.DEFAULTS;
        boolean topLevelSet = reader.section() != null && reader.section().isSet("reach-scale");
        ConfigReader source = topLevelSet ? reader : legacy;
        return new ReachHandicapSettings(source.numberInRange("reach-scale", d.scale(), 0.5, 1.0));
    }

    /**
     * Resolve whether the promoted {@code modules.combo-reach-handicap} feature is
     * on, honouring the 2.4.3-beta legacy shape. When the new module key is unset but
     * the old nested {@code combo-hold.reach-handicap.enabled} reads true, the module
     * is treated as enabled and a loud migration line is emitted naming both keys —
     * never silently ignored. An explicit new module key always wins. The nested
     * legacy block rides the RESOLVED combo-hold section (a 2.4.3 config carries it
     * in config.yml — the moved-section fallback — while a block moved wholesale
     * into combo.yml still resolves there), so a straight 2.4.3 → 2.5.2 upgrade
     * keeps its tuned nested scale.
     */
    private static boolean resolveReachHandicapEnabled(
            ConfigReader modules, ConfigReader comboHold, ConfigIssues issues) {
        boolean moduleKeySet = modules.section() != null
                && modules.section().isSet(Feature.COMBO_REACH_HANDICAP.yamlKey());
        if (moduleKeySet) {
            return modules.flag(Feature.COMBO_REACH_HANDICAP.yamlKey(),
                    Feature.COMBO_REACH_HANDICAP.defaultEnabled());
        }
        ConfigReader legacy = comboHold.sub("reach-handicap");
        if (legacy.flag("enabled", false)) {
            issues.add("config.yml: the reach handicap moved to its own module in 2.4.4 — "
                    + "modules.combo-reach-handicap is unset, but the legacy "
                    + "combo-hold.reach-handicap.enabled reads true, so it is honoured (enabled) for "
                    + "this release. Set modules.combo-reach-handicap: true and move reach-scale to a "
                    + "top-level combo-reach-handicap block in combo.yml; the nested block is deprecated.");
            return true;
        }
        return false;
    }

    private static PotFillSettings parsePotFill(ConfigReader reader) {
        PotFillSettings d = PotFillSettings.DEFAULTS;
        // A blank permission would open the command to everyone; text() already
        // keeps the fallback (and warns) on a blank/non-string value.
        return new PotFillSettings(
                reader.text("permission", d.permission()),
                reader.numberAtLeast("cost-per-potion", d.costPerPotion(), 0.0));
    }

    /**
     * The {@code drop-protection} tunables: the killer-only pickup window in
     * whole seconds (at least 1, capped at an hour so a typo cannot lock loot
     * forever) and the per-player glow colour (GOLD or YELLOW — the two named
     * team colours near the requested gold tint). Every knob is an era-exact
     * no-op only because the module defaults OFF; enabled-at-DEFAULTS is the
     * shipped 15-second gold-glow feel.
     */
    private static DropProtectionSettings parseDropProtection(ConfigReader reader) {
        DropProtectionSettings d = DropProtectionSettings.DEFAULTS;
        return new DropProtectionSettings(
                reader.intClamped("seconds", d.seconds(), 1, 3600),
                reader.oneOf("glow-color", d.glowColor(), DropProtectionSettings.GlowColor.class));
    }

    private static FastPotsSettings parseFastPots(ConfigReader reader) {
        FastPotsSettings d = FastPotsSettings.DEFAULTS;
        return new FastPotsSettings(
                reader.numberClamped("angle-degrees", d.angleDegrees(),
                        FastPotsSettings.MIN_ANGLE, FastPotsSettings.MAX_ANGLE),
                reader.numberClamped("min-speed-multiplier", d.minSpeedMultiplier(),
                        FastPotsSettings.MIN_MIN_MULTIPLIER, FastPotsSettings.MAX_MIN_MULTIPLIER),
                reader.numberClamped("max-speed-multiplier", d.maxSpeedMultiplier(),
                        FastPotsSettings.MIN_MAX_MULTIPLIER, FastPotsSettings.MAX_MAX_MULTIPLIER),
                reader.numberClamped("lead-ticks", d.leadTicks(),
                        FastPotsSettings.MIN_LEAD, FastPotsSettings.MAX_LEAD));
    }

    private static OffhandSettings parseOffhand(ConfigReader reader) {
        boolean whitelist = reader.flag("whitelist", OffhandSettings.DEFAULTS.whitelist());
        List<String> names = reader.stringList("items", List.of());
        Set<Material> items = names.stream()
                .map(name -> matchMaterial(name, "disable-offhand.items", reader))
                .filter(material -> material != null)
                .collect(Collectors.toUnmodifiableSet());
        String deniedMessage = reader.text("denied-message", OffhandSettings.DEFAULTS.deniedMessage());
        return new OffhandSettings(whitelist, items, deniedMessage);
    }

    private static Material matchMaterial(String name, String path, ConfigReader reader) {
        Material material = Material.matchMaterial(name);
        if (material == null) {
            reader.issues().warn(path, "unknown material '" + name + "' — skipped", "(none)");
        }
        return material;
    }

    private static AnticheatSettings parseAnticheat(ConfigReader reader) {
        AnticheatSettings d = AnticheatSettings.DEFAULTS;
        AnticheatMode mode = reader.oneOf("mode", d.mode(), AnticheatMode.class);
        List<String> known = reader.section() == null || !reader.section().isList("known")
                ? d.knownPlugins()
                : List.copyOf(reader.section().getStringList("known"));
        return new AnticheatSettings(mode, known);
    }

    private static DebugSettings parseDebug(ConfigReader reader) {
        boolean enabled = reader.flag("enabled", DebugSettings.DEFAULTS.enabled());
        ConfigurationSection categories = reader.section() == null
                ? null
                : reader.section().getConfigurationSection("categories");
        if (categories == null) {
            return new DebugSettings(enabled, DebugSettings.DEFAULTS.categories());
        }
        Set<String> active = new HashSet<>();
        for (String key : categories.getKeys(false)) {
            if (categories.getBoolean(key)) {
                active.add(key.toLowerCase(Locale.ROOT));
            }
        }
        return new DebugSettings(enabled, Set.copyOf(active));
    }

    private static ConfigReader reader(Configuration root, String path, String file, ConfigIssues issues) {
        return new ConfigReader(root.getConfigurationSection(path), file + ": " + path, issues);
    }
}
