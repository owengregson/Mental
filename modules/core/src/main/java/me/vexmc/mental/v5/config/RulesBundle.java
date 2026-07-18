package me.vexmc.mental.v5.config;

import java.util.Map;
import java.util.Optional;

/**
 * One rules bundle — the third preset mechanism (2.8.x), the knockback-profile
 * and Combat-Effects-preset models mirrored once more, for the WHOLE feature
 * surface at once. Where a knockback profile is one complete <em>feel</em> and an
 * effects preset one complete cosmetic <em>tune</em>, a bundle is one complete
 * <em>ruleset</em>: a named macro that flips a batch of module toggles (and,
 * optionally, the active knockback profile and effects preset) in a single atomic
 * apply.
 *
 * <p>A bundle is a <b>macro, not a mode</b>. There is no live "active bundle"
 * state anywhere — {@code Management.applyBundle} writes the bundle's keys into the
 * machine overlay and reloads once, and the bundle is then forgotten. Two bundles
 * that set the same key to different values do not conflict at rest; whichever was
 * applied last wins, exactly as if the operator had toggled the modules by hand.
 * The three shipped bundles ({@code ct8c}, {@code signature}, {@code vanilla})
 * each state <em>every</em> module they own explicitly, so applying one lands the
 * server in a fully-known configuration regardless of what preceded it.</p>
 *
 * <p>The record is pure data: {@link RulesBundleParser} builds it from a bundle
 * YAML file, and {@code Management.applyBundle} validates + expands it into
 * overlay keys. {@link #modules} preserves the file's declaration order (a
 * {@code LinkedHashMap}), so the resulting overlay batch is deterministic — the
 * property the tester's {@code RulesBundleSuite} pins.</p>
 *
 * @param name           the bundle stem (its file name without {@code .yml})
 * @param displayName    the human label shown in the GUI picker
 * @param description    a one-line summary shown under the tile
 * @param knockbackProfile the profile to select, or empty to leave it untouched
 * @param effectsPreset  the effects preset to select, or empty to leave it untouched
 * @param modules        {@code yamlKey -> desired enabled state}, in file order; a
 *                       key absent from this map is left exactly as configured
 * @param settings       optional dotted-overlay-key -> value pairs (the defaults
 *                       ARE the era values for every shipped bundle, so this is
 *                       empty for all three — it exists for operator-authored
 *                       bundles that want to pin a specific tunable)
 */
public record RulesBundle(
        String name,
        String displayName,
        String description,
        Optional<String> knockbackProfile,
        Optional<String> effectsPreset,
        Map<String, Boolean> modules,
        Map<String, String> settings) {
}
