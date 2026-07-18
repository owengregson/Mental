package me.vexmc.mental.v5.config;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Parses rules-bundle files ({@code bundles/<name>.yml}) into {@link RulesBundle}
 * records — the {@link EffectsPresetParser} model mirrored a third time, for the
 * whole-ruleset macro mechanism (2.8.x).
 *
 * <p>Unlike the knockback and effects parsers, a bundle has <b>no LEGACY / no-op
 * default</b>: a bundle exists only to state a set of module toggles, so a file
 * with no {@code modules:} map is meaningless and earns one loud issue (mandate
 * B10). {@code parse(empty)} therefore yields a bundle with an empty module map
 * AND a recorded issue — it never silently resolves to "change nothing", which
 * would be an invisible no-op the operator could not diagnose. The knockback
 * profile and effects preset are optional (a bundle that only flips modules leaves
 * both untouched), and both are lower-cased to match the selection keys.</p>
 *
 * <p>The {@code modules:} map is read in file order into a {@link LinkedHashMap}
 * so the overlay batch {@code Management.applyBundle} builds is deterministic. The
 * optional {@code settings:} block is read as a DEEP tree — its leaf paths become
 * dotted overlay keys (e.g. {@code charged-attacks.miss-recovery-ticks}), so a
 * bundle can pin an arbitrary tunable through the same overlay seam the GUI uses.
 * No shipped bundle carries settings; the defaults are the era values.</p>
 */
public final class RulesBundleParser {

    private RulesBundleParser() {}

    /** Parses every bundle source keyed by stem into a name → {@link RulesBundle} library. */
    public static Map<String, RulesBundle> parseLibrary(
            Map<String, Configuration> sources, ConfigIssues issues) {
        Map<String, RulesBundle> byName = new TreeMap<>();
        for (Map.Entry<String, Configuration> entry : sources.entrySet()) {
            byName.put(entry.getKey(), parse(entry.getKey(), entry.getValue(), issues));
        }
        return byName;
    }

    /** Parses one bundle file into a {@link RulesBundle}; an absent modules map warns loudly. */
    public static RulesBundle parse(String name, Configuration file, ConfigIssues issues) {
        String label = ConfigStore.BUNDLES_DIR + "/" + name + ".yml";
        String displayName = file.getString("display-name", name);
        String description = file.getString("description", "");
        Optional<String> profile = lowerOptional(file.getString("knockback-profile"));
        Optional<String> preset = lowerOptional(file.getString("effects-preset"));

        Map<String, Boolean> modules = new LinkedHashMap<>();
        ConfigurationSection moduleSection = file.getConfigurationSection("modules");
        if (moduleSection == null || moduleSection.getKeys(false).isEmpty()) {
            issues.add(label + ": has no 'modules:' map — a rules bundle must state the"
                    + " module toggles it sets (there is no change-nothing default)");
        } else {
            for (String key : moduleSection.getKeys(false)) {
                modules.put(key, moduleSection.getBoolean(key));
            }
        }

        Map<String, String> settings = new LinkedHashMap<>();
        ConfigurationSection settingSection = file.getConfigurationSection("settings");
        if (settingSection != null) {
            // getKeys(true) yields the full dotted path for every nested leaf; a value
            // key (not itself a section) becomes a dotted overlay key verbatim, so a
            // bundle authored as nested YAML produces the same key the GUI would write.
            for (String key : settingSection.getKeys(true)) {
                if (!settingSection.isConfigurationSection(key)) {
                    settings.put(key, String.valueOf(settingSection.get(key)));
                }
            }
        }

        return new RulesBundle(name, displayName, description, profile, preset, modules, settings);
    }

    /** A trimmed, lower-cased optional — empty for an absent or blank value. */
    private static Optional<String> lowerOptional(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
    }
}
