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
import me.vexmc.mental.v5.config.settings.DebugSettings;
import me.vexmc.mental.v5.config.settings.FastPotsSettings;
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
 * + latency-compensation.yml + profiles/*.yml) into one immutable {@link Snapshot},
 * with the warn-and-fallback issue list. This is the retired
 * {@code MentalConfig.reload} re-expressed over the descriptor registry: every
 * module toggle keys off {@link Feature#yamlKey()}, every settings record keys
 * off {@link Feature#settingsKey()}, and {@code parse(empty) == full defaults}.
 * The per-record parse logic is ported field-for-field from the retired
 * {@code config.*Settings} records (whose parse methods it absorbs).
 */
public final class SnapshotParser {

    /** The parsed snapshot plus every warn-and-fallback issue encountered. */
    public record Result(Snapshot snapshot, List<String> issues) {}

    private SnapshotParser() {}

    public static Result parse(
            Configuration main,
            Configuration knockback,
            Configuration hitReg,
            Configuration compensation,
            Map<String, Configuration> profiles) {

        ConfigIssues issues = new ConfigIssues();
        ConfigReader modules = new ConfigReader(
                main.getConfigurationSection("modules"), "config.yml: modules", issues);

        // The combo reach handicap was promoted to its own module in 2.4.4; its
        // enablement carries a loud legacy-key migration, so it is resolved once here
        // and the loop below reuses it (COMBO_HOLD likewise, to avoid a double read).
        boolean comboHoldOn =
                modules.flag(Feature.COMBO_HOLD.yamlKey(), Feature.COMBO_HOLD.defaultEnabled());
        boolean reachHandicapOn = resolveReachHandicapEnabled(modules, main, issues);

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
            builder.put(feature.settingsKey(),
                    settingsFor(feature, main, knockback, hitReg, compensation, issues));
        }
        // Loud dependency warning: the handicap only ever engages while a combo is
        // held, so enabling it without combo-hold ships a dormant lever — never
        // silently, per the zero-touch contract's honesty rule.
        if (reachHandicapOn && !comboHoldOn) {
            issues.add("config.yml: modules.combo-reach-handicap is enabled but modules.combo-hold "
                    + "is off — the reach handicap only applies while a combo is held, so it will "
                    + "never engage. Enable combo-hold too, or turn combo-reach-handicap off.");
        }

        builder.profiles(ProfileParser.parseSection(
                reader(knockback, "knockback", "knockback.yml", issues), profiles, issues));
        builder.anticheat(parseAnticheat(reader(main, "anticheat", "config.yml", issues)));
        builder.debug(parseDebug(reader(main, "debug", "config.yml", issues)));
        builder.ocmCoordination(reader(main, "compatibility", "config.yml", issues)
                .oneOf("old-combat-mechanics", OcmCoordination.AUTO, OcmCoordination.class));
        // bStats metrics toggle (spec §13). Parse-with-default: an absent
        // `metrics` section (or key) reads true silently — the frozen bundled
        // config need not carry it. Warn-and-fallback on a non-boolean value.
        builder.metricsEnabled(reader(main, "metrics", "config.yml", issues).flag("enabled", true));

        return new Result(builder.build(), issues.all());
    }

    private static Object settingsFor(
            Feature feature,
            Configuration main,
            Configuration knockback,
            Configuration hitReg,
            Configuration compensation,
            ConfigIssues issues) {
        return switch (feature) {
            case HIT_REGISTRATION ->
                    parseHitReg(reader(hitReg, "hit-registration", "hit-registration.yml", issues));
            case LATENCY_COMPENSATION ->
                    parseCompensation(reader(compensation, "latency-compensation",
                            "latency-compensation.yml", issues));
            case FISHING_KNOCKBACK ->
                    parseFishing(reader(knockback, "fishing-knockback", "knockback.yml", issues));
            case PROJECTILE_KNOCKBACK ->
                    parseProjectile(reader(knockback, "projectile-knockback", "knockback.yml", issues));
            case CRAFTING -> parseCrafting(reader(main, "disable-crafting", "config.yml", issues));
            case OFFHAND -> parseOffhand(reader(main, "disable-offhand", "config.yml", issues));
            case COMBO_HOLD -> parseCombo(reader(main, "combo-hold", "config.yml", issues));
            case COMBO_REACH_HANDICAP -> parseReachHandicap(
                    reader(main, "combo-reach-handicap", "config.yml", issues),
                    reader(main, "combo-hold", "config.yml", issues).sub("reach-handicap"));
            case POT_FILL -> parsePotFill(reader(main, "pot-fill", "config.yml", issues));
            case FAST_POTS -> parseFastPots(reader(main, "fast-pots", "config.yml", issues));
            default -> NoSettings.DEFAULTS;
        };
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
        return new CompensationSettings(
                probeStrategy,
                reader.intAtLeast("ping-offset-ms", d.pingOffsetMillis(), 0),
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
        return new ComboSettings(
                reader.intAtLeast("min-hits", d.minHits(), 1),
                reader.intAtLeast("max-gap-ticks", d.maxGapTicks(), 1),
                reader.intAtLeast("grounded-run-ticks", d.groundedRunTicks(), 1),
                reader.numberAtLeast("blowout-blocks", d.blowoutBlocks(), 0.0),
                reader.numberAtLeast("target", d.target(), 0.5),
                reader.numberAtLeast("gain", d.gain(), 0.0),
                minFactor,
                maxFactor,
                reader.intAtLeast("window-ticks", d.windowTicks(), 1),
                reader.oneOf("target-mode", d.targetMode(), TargetMode.class),
                reader.numberAtLeast("hit-cap", d.hitCap(), 0.5));
    }

    /**
     * The promoted combo reach-handicap scale (2.4.4). Reads the top-level
     * {@code combo-reach-handicap.reach-scale}; on an in-place upgrade whose config
     * predates the promotion it falls back to the legacy nested
     * {@code combo-hold.reach-handicap.reach-scale} for one release window, so a
     * tuned value is never silently lost. A handicap only ever shortens reach, so
     * the scale is confined to {@code [0.5, 1.0]}; anything outside warns and 0.8
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
     * never silently ignored. An explicit new module key always wins.
     */
    private static boolean resolveReachHandicapEnabled(
            ConfigReader modules, Configuration main, ConfigIssues issues) {
        boolean moduleKeySet = modules.section() != null
                && modules.section().isSet(Feature.COMBO_REACH_HANDICAP.yamlKey());
        if (moduleKeySet) {
            return modules.flag(Feature.COMBO_REACH_HANDICAP.yamlKey(),
                    Feature.COMBO_REACH_HANDICAP.defaultEnabled());
        }
        ConfigReader legacy = reader(main, "combo-hold", "config.yml", issues).sub("reach-handicap");
        if (legacy.flag("enabled", false)) {
            issues.add("config.yml: the reach handicap moved to its own module in 2.4.4 — "
                    + "modules.combo-reach-handicap is unset, but the legacy "
                    + "combo-hold.reach-handicap.enabled reads true, so it is honoured (enabled) for "
                    + "this release. Set modules.combo-reach-handicap: true and move reach-scale to a "
                    + "top-level combo-reach-handicap block; the nested block is deprecated.");
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

    private static FastPotsSettings parseFastPots(ConfigReader reader) {
        FastPotsSettings d = FastPotsSettings.DEFAULTS;
        return new FastPotsSettings(
                reader.numberClamped("angle-degrees", d.angleDegrees(),
                        FastPotsSettings.MIN_ANGLE, FastPotsSettings.MAX_ANGLE),
                reader.numberClamped("speed-multiplier", d.speedMultiplier(),
                        FastPotsSettings.MIN_MULTIPLIER, FastPotsSettings.MAX_MULTIPLIER));
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
