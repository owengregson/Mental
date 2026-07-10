package me.vexmc.mental.v5.config;

import java.util.ArrayList;
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
import me.vexmc.mental.v5.config.settings.DebugSettings;
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
import org.bukkit.configuration.MemoryConfiguration;

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
        // The reach handicap no longer depends on combo-hold: since the 2.4.5
        // detection/servo split it drives combo DETECTION itself (either keeper does),
        // so it engages on its own combos even with the pocket servo off. No
        // dependency warning is emitted; the two combo modules toggle independently.

        builder.profiles(ProfileParser.parseSection(
                reader(knockback, "knockback", "knockback.yml", issues), profiles, issues));
        builder.anticheat(parseAnticheat(reader(main, "anticheat", "config.yml", issues)));
        builder.debug(parseDebug(reader(main, "debug", "config.yml", issues)));
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
            case HIT_FEEDBACK -> parseHitFeedback(reader(main, "hit-feedback", "config.yml", issues));
            case DAMAGE_INDICATORS ->
                    parseDamageIndicators(reader(main, "damage-indicators", "config.yml", issues));
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
                reader.numberClamped("min-speed-multiplier", d.minSpeedMultiplier(),
                        FastPotsSettings.MIN_MIN_MULTIPLIER, FastPotsSettings.MAX_MIN_MULTIPLIER),
                reader.numberClamped("max-speed-multiplier", d.maxSpeedMultiplier(),
                        FastPotsSettings.MIN_MAX_MULTIPLIER, FastPotsSettings.MAX_MAX_MULTIPLIER),
                reader.numberClamped("lead-ticks", d.leadTicks(),
                        FastPotsSettings.MIN_LEAD, FastPotsSettings.MAX_LEAD));
    }

    private static HitFeedbackSettings parseHitFeedback(ConfigReader reader) {
        HitFeedbackSettings d = HitFeedbackSettings.DEFAULTS;
        HitFeedbackSettings.Preset preset =
                reader.oneOf("preset", d.preset(), HitFeedbackSettings.Preset.class);
        // The custom lists are parsed regardless of the preset (so a `custom` switch
        // finds them ready); the record's sounds()/particles()/lowHealthSounds() pick
        // the effective set. The low-health threshold is a flat HEARTS ceiling.
        return new HitFeedbackSettings(
                preset,
                parseSounds(reader, "sounds", d.customSounds()),
                parseParticles(reader, d.customParticles()),
                parseSounds(reader, "low-health-sounds", d.customLowHealthSounds()),
                reader.numberClamped("low-health-threshold-hearts", d.lowHealthThresholdHearts(), 0.0, 100.0));
    }

    /**
     * A list-of-records sound list under {@code key} — the config's first
     * list-of-records shape, shared by {@code sounds:} and the low-health extra
     * layer's {@code low-health-sounds:}. Each map entry is re-wrapped into its own
     * {@link ConfigReader} (over a MemoryConfiguration-backed section) so every field
     * read runs through the same warn-and-fallback contract the flat knobs use. A
     * blank/absent name skips the entry loudly; the survivors are returned immutable.
     */
    private static List<HitFeedbackSettings.SoundSpec> parseSounds(
            ConfigReader reader, String key, List<HitFeedbackSettings.SoundSpec> fallback) {
        ConfigurationSection section = reader.section();
        if (section == null || !section.isSet(key)) {
            return fallback;
        }
        List<HitFeedbackSettings.SoundSpec> sounds = new ArrayList<>();
        List<Map<?, ?>> entries = section.getMapList(key);
        for (int i = 0; i < entries.size(); i++) {
            ConfigReader entry = listEntry(reader, key, i, entries.get(i));
            String name = entry.text("sound", "");
            if (name.isBlank()) {
                skipUnnamed(entry, "sound");
                continue;
            }
            sounds.add(new HitFeedbackSettings.SoundSpec(
                    name,
                    (float) entry.numberClamped("volume", 1.0,
                            HitFeedbackSettings.MIN_VOLUME, HitFeedbackSettings.MAX_VOLUME),
                    (float) entry.numberClamped("pitch", 1.0,
                            HitFeedbackSettings.MIN_PITCH, HitFeedbackSettings.MAX_PITCH)));
        }
        return List.copyOf(sounds);
    }

    /** The {@code particles:} list, re-wrapped per entry like {@link #parseSounds}. */
    private static List<HitFeedbackSettings.ParticleSpec> parseParticles(
            ConfigReader reader, List<HitFeedbackSettings.ParticleSpec> fallback) {
        ConfigurationSection section = reader.section();
        if (section == null || !section.isSet("particles")) {
            return fallback;
        }
        List<HitFeedbackSettings.ParticleSpec> particles = new ArrayList<>();
        List<Map<?, ?>> entries = section.getMapList("particles");
        for (int i = 0; i < entries.size(); i++) {
            ConfigReader entry = listEntry(reader, "particles", i, entries.get(i));
            String name = entry.text("particle", "");
            if (name.isBlank()) {
                skipUnnamed(entry, "particle");
                continue;
            }
            int countMin = Math.min(entry.intAtLeast("count-min", 1, 0), HitFeedbackSettings.MAX_COUNT);
            int countMax = Math.min(entry.intAtLeast("count-max", countMin, countMin),
                    HitFeedbackSettings.MAX_COUNT);
            ConfigReader spread = entry.sub("spread");
            particles.add(new HitFeedbackSettings.ParticleSpec(
                    name,
                    entry.text("block", ""),
                    countMin,
                    countMax,
                    entry.oneOf("mode", HitFeedbackSettings.Mode.EMANATE, HitFeedbackSettings.Mode.class),
                    (float) entry.numberClamped("speed", 0.15, 0.0, 2.0),
                    spread.numberClamped("x", 0.2, 0.0, 4.0),
                    spread.numberClamped("y", 0.3, 0.0, 4.0),
                    spread.numberClamped("z", 0.2, 0.0, 4.0)));
        }
        return List.copyOf(particles);
    }

    /** Re-wrap one list-of-maps entry into a per-entry reader with its own warn prefix. */
    private static ConfigReader listEntry(ConfigReader parent, String listKey, int index, Map<?, ?> map) {
        ConfigurationSection entry = new MemoryConfiguration().createSection("entry", map);
        return new ConfigReader(entry, parent.prefix() + "." + listKey + "[" + index + "]", parent.issues());
    }

    /**
     * A list entry with no usable name is dropped. {@link ConfigReader#text} already
     * warned if a name was present-but-blank, so this warns only for the absent case —
     * every skipped entry is announced exactly once.
     */
    private static void skipUnnamed(ConfigReader entry, String nameKey) {
        if (entry.section() == null || !entry.section().isSet(nameKey)) {
            entry.issues().warn(entry.prefix() + "." + nameKey,
                    "no " + nameKey + " name — entry skipped", "(skipped)");
        }
    }

    private static DamageIndicatorsSettings parseDamageIndicators(ConfigReader reader) {
        DamageIndicatorsSettings d = DamageIndicatorsSettings.DEFAULTS;
        return new DamageIndicatorsSettings(
                reader.intClamped("lifetime-ticks", d.lifetimeTicks(),
                        DamageIndicatorsSettings.MIN_LIFETIME, DamageIndicatorsSettings.MAX_LIFETIME),
                reader.numberClamped("ring-radius", d.ringRadius(), 0.0,
                        DamageIndicatorsSettings.MAX_RADIUS),
                reader.numberClamped("height-jitter", d.heightJitter(), 0.0,
                        DamageIndicatorsSettings.MAX_JITTER),
                reader.numberClamped("launch-vertical", d.launchVertical(), 0.0,
                        DamageIndicatorsSettings.MAX_LAUNCH),
                reader.numberClamped("launch-outward", d.launchOutward(), 0.0,
                        DamageIndicatorsSettings.MAX_LAUNCH),
                reader.numberClamped("gravity", d.gravity(), 0.0,
                        DamageIndicatorsSettings.MAX_GRAVITY),
                reader.numberClamped("drag", d.drag(),
                        DamageIndicatorsSettings.MIN_DRAG, DamageIndicatorsSettings.MAX_DRAG),
                reader.text("text", d.text()),
                reader.text("crit-text", d.critText()),
                reader.numberClamped("crit-threshold-hearts", d.critThresholdHearts(), 0.0, 100.0));
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
