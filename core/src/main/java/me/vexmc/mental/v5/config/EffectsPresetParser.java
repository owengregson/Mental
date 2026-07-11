package me.vexmc.mental.v5.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import me.vexmc.mental.v5.config.settings.DamageIndicatorsSettings;
import me.vexmc.mental.v5.config.settings.DeathEffectsSettings;
import me.vexmc.mental.v5.config.settings.HitFeedbackSettings;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

/**
 * Parses Combat Effects preset files ({@code effects/presets/<name>.yml}) and
 * resolves the {@code effects.yml} selection — the {@code ProfileParser} model
 * mirrored for the FEEDBACK family (2.5.3). One preset file carries the three
 * module sections; {@code parse(empty)} yields every settings DEFAULTS record
 * (the vanilla tune — the era-exact no-op), and every knob keeps the
 * warn-and-fallback contract through {@link ConfigReader}. The section and
 * list parsers were lifted verbatim from {@code SnapshotParser} (which parsed
 * the same schemas out of the retired per-module files), minus the per-module
 * {@code preset:} enum the redesign killed; {@link Migrations} reuses the list
 * parsers to resolve a 2.5.2 tree's old effective values during the 3 → 4
 * import.
 */
public final class EffectsPresetParser {

    /** The parsed library: the selected preset name plus every loaded preset by name. */
    public record Library(String selected, Map<String, EffectsPreset> byName) {

        /**
         * The preset the three FEEDBACK settings records come from: the
         * selected entry, or the in-code vanilla constant when even the
         * vanilla file is missing (a torn install parses to the era-exact
         * no-op, never a crash).
         */
        public EffectsPreset effective() {
            return byName.getOrDefault(selected, EffectsPreset.VANILLA);
        }
    }

    private EffectsPresetParser() {}

    /**
     * Parses every preset source and resolves the selection. An unknown
     * selected name earns one loud issue and the vanilla preset stands in —
     * never a silent change of tune; the selection itself defaults to vanilla
     * (effective = overlay ?? file ?? default, the overlay having been applied
     * onto the {@code effects.yml} root before this runs).
     */
    public static Library parseSection(
            ConfigReader selection, Map<String, Configuration> presetSources, ConfigIssues issues) {
        Map<String, EffectsPreset> byName = new TreeMap<>();
        for (Map.Entry<String, Configuration> entry : presetSources.entrySet()) {
            byName.put(entry.getKey(), parse(entry.getKey(), entry.getValue(), issues));
        }
        String selected = selection.text("preset", EffectsPreset.DEFAULT_NAME)
                .trim().toLowerCase(Locale.ROOT);
        if (!byName.containsKey(selected) && !EffectsPreset.DEFAULT_NAME.equals(selected)) {
            issues.add("effects.yml: preset '" + selected + "' matches no file in "
                    + ConfigStore.EFFECTS_PRESETS_DIR + "/ — the vanilla preset stands in");
            selected = EffectsPreset.DEFAULT_NAME;
        }
        return new Library(selected, byName);
    }

    /** Parses one preset file's three sections into an {@link EffectsPreset}. */
    public static EffectsPreset parse(String name, Configuration file, ConfigIssues issues) {
        String label = ConfigStore.EFFECTS_PRESETS_DIR + "/" + name + ".yml";
        return new EffectsPreset(
                name,
                file.getString("display-name", name),
                file.getString("description", ""),
                parseHitFeedback(reader(file, "hit-feedback", label, issues)),
                parseDamageIndicators(reader(file, "damage-indicators", label, issues)),
                parseDeathEffects(reader(file, "death-effects", label, issues)));
    }

    private static ConfigReader reader(
            Configuration file, String section, String label, ConfigIssues issues) {
        return new ConfigReader(file.getConfigurationSection(section),
                label + ": " + section, issues);
    }

    /* ------------------------------------------------------------------ */
    /*  Section parsers (schemas unchanged from the 2.5.2 module files)    */
    /* ------------------------------------------------------------------ */

    static HitFeedbackSettings parseHitFeedback(ConfigReader reader) {
        HitFeedbackSettings d = HitFeedbackSettings.DEFAULTS;
        return new HitFeedbackSettings(
                parseSounds(reader, "sounds", d.sounds()),
                parseParticles(reader, d.particles()),
                parseSounds(reader, "low-health-sounds", d.lowHealthSounds()),
                reader.numberClamped("low-health-threshold-hearts", d.lowHealthThresholdHearts(), 0.0, 100.0));
    }

    static DamageIndicatorsSettings parseDamageIndicators(ConfigReader reader) {
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

    static DeathEffectsSettings parseDeathEffects(ConfigReader reader) {
        DeathEffectsSettings d = DeathEffectsSettings.DEFAULTS;
        return new DeathEffectsSettings(
                reader.flag("lightning", d.lightning()),
                parseSounds(reader, "sounds", d.sounds()),
                parseParticles(reader, d.particles()),
                parseFireworkColors(reader, d.fireworkColors()));
    }

    /* ------------------------------------------------------------------ */
    /*  List-of-records parsers                                            */
    /* ------------------------------------------------------------------ */

    /**
     * A list-of-records sound list under {@code key} — the config's first
     * list-of-records shape, shared by {@code sounds:} and the low-health extra
     * layer's {@code low-health-sounds:}. Each map entry is re-wrapped into its own
     * {@link ConfigReader} (over a MemoryConfiguration-backed section) so every field
     * read runs through the same warn-and-fallback contract the flat knobs use. A
     * blank/absent name skips the entry loudly; the survivors are returned immutable.
     */
    static List<HitFeedbackSettings.SoundSpec> parseSounds(
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
    static List<HitFeedbackSettings.ParticleSpec> parseParticles(
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

    /**
     * The {@code firework:} block's {@code colors:} list — RRGGBB hex strings
     * (a leading {@code #} is tolerated, same contract as the dust specs'
     * {@code block} field), stored as RGB ints. An absent block or an empty
     * list means no firework ships; a malformed entry warns once and is
     * skipped, so one typo cannot silence the rest of the blast.
     */
    static List<Integer> parseFireworkColors(ConfigReader reader, List<Integer> fallback) {
        ConfigReader firework = reader.sub("firework");
        if (firework.section() == null) {
            return fallback;
        }
        List<Integer> colors = new ArrayList<>();
        for (String hex : firework.stringList("colors", List.of())) {
            Integer rgb = hexColor(hex);
            if (rgb == null) {
                firework.issues().warn(firework.prefix() + ".colors",
                        "'" + hex + "' is not a RRGGBB hex color — skipped", "(skipped)");
                continue;
            }
            colors.add(rgb);
        }
        return List.copyOf(colors);
    }

    /** A six-digit RRGGBB hex (optional {@code #}) as an RGB int, or null if malformed. */
    private static Integer hexColor(String hex) {
        if (hex == null) {
            return null;
        }
        String normalized = hex.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("#")) {
            normalized = normalized.substring(1);
        }
        if (normalized.length() != 6) {
            return null;
        }
        for (int i = 0; i < 6; i++) {
            if (Character.digit(normalized.charAt(i), 16) < 0) {
                return null;
            }
        }
        return Integer.parseInt(normalized, 16);
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
}
