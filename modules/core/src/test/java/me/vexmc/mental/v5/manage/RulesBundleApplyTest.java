package me.vexmc.mental.v5.manage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import me.vexmc.mental.v5.config.ConfigIssues;
import me.vexmc.mental.v5.config.RulesBundle;
import me.vexmc.mental.v5.config.RulesBundleParser;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * The bundle apply PLAN — the pure validate-and-expand step behind
 * {@code Management.applyBundle}, tested without a live plugin. Pins the exact
 * overlay batch the shipped {@code ct8c} bundle produces (the same keys the
 * tester's RulesBundleSuite asserts), and the reject-all-on-any-unknown atomicity:
 * one bad key leaves the overlay empty.
 */
class RulesBundleApplyTest {

    /** The profile/preset names a well-configured server has loaded. */
    private static final Set<String> KNOWN_PROFILES =
            Set.of("ct8c", "signature", "modern-vanilla", "legacy-1.7");
    private static final Set<String> KNOWN_PRESETS = Set.of("signature", "custom");

    private static RulesBundle bundleResource(String stem) {
        try (InputStream stream = RulesBundleApplyTest.class.getClassLoader()
                .getResourceAsStream("bundles/" + stem + ".yml")) {
            assertNotNull(stream, () -> "missing bundle resource: bundles/" + stem + ".yml");
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            ConfigIssues issues = new ConfigIssues();
            RulesBundle bundle = RulesBundleParser.parse(stem, yaml, issues);
            assertTrue(issues.clean(), () -> "shipped bundle " + stem + " must parse clean: " + issues.all());
            return bundle;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Test
    void ct8cBundleExpandsToTheExactOverlayBatch() {
        RulesBundle ct8c = bundleResource("ct8c");
        Management.BundlePlan plan = Management.planBundle(ct8c, KNOWN_PROFILES, KNOWN_PRESETS);

        assertTrue(plan.errors().isEmpty(), () -> "the shipped ct8c bundle must validate: " + plan.errors());

        Map<String, Object> expected = new LinkedHashMap<>();
        for (String key : List.of("weapon-attack-speeds", "charged-attacks", "ct8c-damage",
                "ct8c-crits", "ct8c-sweep", "ct8c-iframes", "ct8c-shields", "ct8c-regen",
                "ct8c-consumables", "ct8c-potions", "ct8c-reach", "ct8c-projectiles", "cleaving")) {
            expected.put("modules." + key, true);
        }
        for (String key : List.of("old-armour-strength", "old-armour-durability", "old-critical-hits",
                "old-tool-durability", "sword-blocking", "attack-cooldown", "disable-attack-sounds",
                "disable-sword-sweep", "old-golden-apples", "disable-enderpearl-cooldown",
                "old-player-regen", "old-potion-durations", "old-potion-values", "disable-crafting",
                "disable-offhand", "old-hitboxes", "combo-hold", "combo-reach-handicap", "pot-fill",
                "fast-pots", "hit-feedback", "damage-indicators", "death-effects", "drop-protection")) {
            expected.put("modules." + key, false);
        }
        expected.put("knockback.profile", "ct8c");

        // Exact key set + values (13 on, 24 off, one profile — 38 keys, no effects.preset).
        assertEquals(expected, plan.overlay());
        assertEquals(38, plan.overlay().size());
        // Deterministic order: CT8c keys, then classics, then the profile.
        assertIterableEquals(new ArrayList<>(expected.keySet()),
                new ArrayList<>(plan.overlay().keySet()));
    }

    @Test
    void signatureBundleWritesTheProfileAndEffectsPresetKeys() {
        RulesBundle signature = bundleResource("signature");
        Management.BundlePlan plan = Management.planBundle(signature, KNOWN_PROFILES, KNOWN_PRESETS);

        assertTrue(plan.errors().isEmpty(), () -> "signature must validate: " + plan.errors());
        assertEquals("signature", plan.overlay().get("knockback.profile"));
        assertEquals("signature", plan.overlay().get("effects.preset"));
        assertEquals(Boolean.TRUE, plan.overlay().get("modules.hit-registration"));
        assertEquals(Boolean.FALSE, plan.overlay().get("modules.ct8c-damage"));
        // 44 module keys + profile + preset.
        assertEquals(46, plan.overlay().size());
    }

    @Test
    void vanillaBundleTurnsEveryModuleOff() {
        RulesBundle vanilla = bundleResource("vanilla");
        Management.BundlePlan plan = Management.planBundle(vanilla, KNOWN_PROFILES, KNOWN_PRESETS);

        assertTrue(plan.errors().isEmpty(), () -> "vanilla must validate: " + plan.errors());
        assertTrue(plan.overlay().entrySet().stream()
                        .filter(e -> e.getKey().startsWith("modules."))
                        .allMatch(e -> Boolean.FALSE.equals(e.getValue())),
                "vanilla sets every module false");
        assertEquals("modern-vanilla", plan.overlay().get("knockback.profile"));
        assertFalse(plan.overlay().containsKey("effects.preset"), "vanilla selects no effects preset");
    }

    @Test
    void anUnknownModuleKeyRejectsEverythingAtomically() {
        RulesBundle bundle = new RulesBundle("bad", "Bad", "",
                Optional.of("ct8c"), Optional.empty(),
                orderedModules("ct8c-damage", true, "not-a-real-feature", true),
                Map.of());

        Management.BundlePlan plan = Management.planBundle(bundle, KNOWN_PROFILES, KNOWN_PRESETS);

        assertFalse(plan.errors().isEmpty(), "an unknown module key must be rejected");
        assertTrue(plan.errors().stream().anyMatch(e -> e.contains("not-a-real-feature")));
        // Atomicity: nothing is applied when any key is bad, not even the good ones.
        assertTrue(plan.overlay().isEmpty(),
                () -> "a rejected plan must carry an empty overlay, got: " + plan.overlay());
    }

    @Test
    void anUnknownProfileRejectsWithNothingApplied() {
        RulesBundle bundle = new RulesBundle("bad", "Bad", "",
                Optional.of("no-such-profile"), Optional.empty(),
                orderedModules("ct8c-damage", true), Map.of());

        Management.BundlePlan plan = Management.planBundle(bundle, KNOWN_PROFILES, KNOWN_PRESETS);

        assertTrue(plan.errors().stream().anyMatch(e -> e.contains("no-such-profile")));
        assertTrue(plan.overlay().isEmpty());
    }

    @Test
    void anUnknownEffectsPresetRejectsWithNothingApplied() {
        RulesBundle bundle = new RulesBundle("bad", "Bad", "",
                Optional.empty(), Optional.of("no-such-preset"),
                orderedModules("ct8c-damage", true), Map.of());

        Management.BundlePlan plan = Management.planBundle(bundle, KNOWN_PROFILES, KNOWN_PRESETS);

        assertTrue(plan.errors().stream().anyMatch(e -> e.contains("no-such-preset")));
        assertTrue(plan.overlay().isEmpty());
    }

    @Test
    void settingsPassThroughAsOverlayKeys() {
        RulesBundle bundle = new RulesBundle("s", "S", "",
                Optional.empty(), Optional.empty(),
                orderedModules("charged-attacks", true),
                Map.of("charged-attacks.miss-recovery-ticks", "4"));

        Management.BundlePlan plan = Management.planBundle(bundle, KNOWN_PROFILES, KNOWN_PRESETS);

        assertTrue(plan.errors().isEmpty());
        assertEquals("4", plan.overlay().get("charged-attacks.miss-recovery-ticks"));
        assertEquals(Boolean.TRUE, plan.overlay().get("modules.charged-attacks"));
    }

    private static Map<String, Boolean> orderedModules(Object... pairs) {
        Map<String, Boolean> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Boolean) pairs[i + 1]);
        }
        return map;
    }
}
