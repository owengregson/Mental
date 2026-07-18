package me.vexmc.mental.v5.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * The rules-bundle parser contract: a well-formed bundle round-trips to the exact
 * record (module order preserved, optionals resolved and lower-cased), a bundle
 * with no {@code modules:} map is flagged loudly (there is NO change-nothing
 * default), and nested {@code settings:} become dotted overlay keys.
 */
class RulesBundleParserTest {

    private static YamlConfiguration yaml(String text) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(new StringReader(text));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
        return config;
    }

    @Test
    void parsesEveryFieldAndPreservesModuleOrder() {
        ConfigIssues issues = new ConfigIssues();
        RulesBundle bundle = RulesBundleParser.parse("ct8c", yaml("""
                display-name: "Combat Test 8c"
                description: "the CT8c snapshot"
                knockback-profile: CT8C
                modules:
                  ct8c-damage: true
                  ct8c-shields: true
                  sword-blocking: false
                """), issues);

        assertTrue(issues.clean(), () -> "a well-formed bundle earns no issues: " + issues.all());
        assertEquals("ct8c", bundle.name());
        assertEquals("Combat Test 8c", bundle.displayName());
        assertEquals("the CT8c snapshot", bundle.description());
        // The profile name is lower-cased to match the selection key.
        assertEquals(Optional.of("ct8c"), bundle.knockbackProfile());
        assertEquals(Optional.empty(), bundle.effectsPreset());
        assertEquals(3, bundle.modules().size());
        assertEquals(Boolean.TRUE, bundle.modules().get("ct8c-damage"));
        assertEquals(Boolean.FALSE, bundle.modules().get("sword-blocking"));
        // File order is preserved (the overlay batch must be deterministic).
        assertIterableEquals(List.of("ct8c-damage", "ct8c-shields", "sword-blocking"),
                new ArrayList<>(bundle.modules().keySet()));
    }

    @Test
    void resolvesEffectsPresetAndReadsNestedSettingsAsDottedKeys() {
        ConfigIssues issues = new ConfigIssues();
        RulesBundle bundle = RulesBundleParser.parse("signature", yaml("""
                display-name: "Signature"
                knockback-profile: signature
                effects-preset: Signature
                modules:
                  hit-feedback: true
                settings:
                  charged-attacks:
                    miss-recovery-ticks: 4
                """), issues);

        assertEquals(Optional.of("signature"), bundle.effectsPreset());
        // A nested settings leaf becomes the full dotted overlay key verbatim.
        assertEquals("4", bundle.settings().get("charged-attacks.miss-recovery-ticks"));
        assertEquals(1, bundle.settings().size());
    }

    @Test
    void aBundleWithNoModulesMapIsFlaggedLoudly() {
        ConfigIssues issues = new ConfigIssues();
        RulesBundle bundle = RulesBundleParser.parse("bare", new YamlConfiguration(), issues);

        assertTrue(bundle.modules().isEmpty(), "an empty bundle has no modules");
        assertFalse(issues.clean(), "a bundle with no modules map must earn a loud issue");
        assertTrue(issues.all().stream().anyMatch(i -> i.contains("bundles/bare.yml")
                        && i.contains("modules")),
                () -> "expected a 'no modules' issue, got: " + issues.all());
    }

    @Test
    void optionalsAreEmptyWhenAbsentOrBlank() {
        ConfigIssues issues = new ConfigIssues();
        RulesBundle bundle = RulesBundleParser.parse("vanilla", yaml("""
                display-name: "Vanilla"
                knockback-profile: "   "
                modules:
                  knockback: false
                """), issues);

        // A blank profile string resolves to empty, not a phantom "" selection.
        assertEquals(Optional.empty(), bundle.knockbackProfile());
        assertEquals(Optional.empty(), bundle.effectsPreset());
        assertTrue(bundle.settings().isEmpty());
    }
}
