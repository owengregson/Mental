package me.vexmc.mental.v5.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.kernel.profile.Presets;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * The profile schema's parsing half (left behind in Phase 1). An empty block
 * parses to LEGACY_17 byte-identical (the era-exact no-op pin); every knob
 * warns-and-falls-back; each bundled resource file parses to its Phase 1
 * {@link Presets} constant; and the retired KnockbackProfilesTest selection
 * cases hold through {@link Snapshot#profileFor}.
 */
class ProfileParserTest {

    private static YamlConfiguration resource(String stem) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                ProfileParserTest.class.getClassLoader().getResourceAsStream("profiles/" + stem + ".yml"),
                StandardCharsets.UTF_8));
    }

    private static ConfigReader knockbackReader(YamlConfiguration profile, String stem) {
        return new ConfigReader(profile.getConfigurationSection("knockback"),
                "profiles/" + stem + ".yml", new ConfigIssues());
    }

    @Test
    void emptyBlockParsesToLegacy17() {
        ConfigReader empty = new ConfigReader(null, "test", new ConfigIssues());
        KnockbackProfile parsed = ProfileParser.parse(
                "legacy-1.7", "Legacy 1.7",
                "The 1.7.10 combat model: vanilla-era values with ledger combos.", empty);
        assertEquals(KnockbackProfile.LEGACY_17, parsed);
    }

    @Test
    void outOfRangeKnobsWarnOnceAndFallBackPerKey() throws Exception {
        YamlConfiguration profile = new YamlConfiguration();
        profile.loadFromString("""
                knockback:
                  base:
                    horizontal: -3.0
                  modifiers:
                    sprint: -1.0
                    combos: "maybe"
                """);
        ConfigIssues issues = new ConfigIssues();
        ConfigReader reader = new ConfigReader(
                profile.getConfigurationSection("knockback"), "profiles/broken.yml", issues);

        KnockbackProfile parsed = ProfileParser.parse("broken", "Broken", "", reader);

        // Each bad knob falls back to the LEGACY_17 value, independently.
        assertEquals(KnockbackProfile.LEGACY_17.base().horizontal(), parsed.base().horizontal());
        assertEquals(KnockbackProfile.LEGACY_17.sprintFactor(), parsed.sprintFactor());
        assertEquals(KnockbackProfile.LEGACY_17.combos(), parsed.combos());
        assertEquals(3, issues.all().size(), () -> "issues: " + issues.all());
        assertTrue(issues.all().stream().anyMatch(w -> w.contains("base.horizontal")));
        assertTrue(issues.all().stream().anyMatch(w -> w.contains("modifiers.sprint")));
        assertTrue(issues.all().stream().anyMatch(w -> w.contains("modifiers.combos")));
    }

    @Test
    void everyBundledResourceParsesToItsPhase1PresetConstant() {
        for (Map.Entry<String, KnockbackProfile> entry : Presets.ALL.entrySet()) {
            String stem = entry.getKey();
            KnockbackProfile expected = entry.getValue();
            YamlConfiguration file = resource(stem);
            KnockbackProfile parsed = ProfileParser.parse(
                    stem,
                    file.getString("display-name", stem),
                    file.getString("description", ""),
                    knockbackReader(file, stem));
            assertEquals(expected, parsed,
                    () -> "bundled profiles/" + stem + ".yml must parse to Presets constant");
        }
    }

    /* ------------------------- selection (Snapshot.profileFor) ------------------------- */

    private static Snapshot parseWith(String knockbackYaml, Map<String, Configuration> profiles)
            throws Exception {
        YamlConfiguration knockback = new YamlConfiguration();
        if (knockbackYaml != null) {
            knockback.loadFromString(knockbackYaml);
        }
        YamlConfiguration empty = new YamlConfiguration();
        return SnapshotParser.parse(empty, knockback, empty, empty, profiles).snapshot();
    }

    private static Map<String, Configuration> profileSources(String... names) throws Exception {
        Map<String, Configuration> sources = new TreeMap<>();
        for (String name : names) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString("display-name: " + name + "\n");
            sources.put(name, yaml);
        }
        return sources;
    }

    @Test
    void noSelectionResolvesToTheBuiltInLegacyDefault() throws Exception {
        Snapshot snapshot = parseWith(null, profileSources("kohi"));
        assertEquals("legacy-1.7", snapshot.profileFor("anything").name());
    }

    @Test
    void explicitLoadedSelectionWinsForEveryWorld() throws Exception {
        Snapshot snapshot = parseWith("knockback:\n  profile: kohi\n", profileSources("kohi"));
        assertEquals("kohi", snapshot.profileFor("world").name());
        assertEquals("kohi", snapshot.profileFor("world_nether").name());
    }

    @Test
    void unknownSelectionFallsBackToLegacyWithOneWarning() throws Exception {
        YamlConfiguration knockback = new YamlConfiguration();
        knockback.loadFromString("knockback:\n  profile: minemen-exact\n");
        YamlConfiguration empty = new YamlConfiguration();
        SnapshotParser.Result result = SnapshotParser.parse(empty, knockback, empty, empty, Map.of());

        assertEquals("legacy-1.7", result.snapshot().profileFor("world").name());
        assertEquals(1, result.issues().size(), () -> "issues: " + result.issues());
        assertTrue(result.issues().get(0).contains("minemen-exact"));
    }

    @Test
    void perWorldOverridesResolveAgainstLoadedProfiles() throws Exception {
        YamlConfiguration knockback = new YamlConfiguration();
        knockback.loadFromString("""
                knockback:
                  profile: arena
                  per-world:
                    duels: arena
                    lobby: nonexistent
                """);
        YamlConfiguration empty = new YamlConfiguration();
        SnapshotParser.Result result = SnapshotParser.parse(
                empty, knockback, empty, empty, profileSources("arena"));
        Snapshot snapshot = result.snapshot();

        assertNotNull(snapshot);
        assertEquals("arena", snapshot.profileFor("duels").name());
        // lobby's unknown profile was dropped → it falls to the server default (arena).
        assertEquals("arena", snapshot.profileFor("lobby").name());
        assertEquals(1, result.issues().size(), () -> "issues: " + result.issues());
        assertTrue(result.issues().get(0).contains("per-world.lobby"));
    }
}
