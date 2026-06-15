package me.vexmc.mental.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConfigReader#stringList}.
 *
 * <p>The method is the shared foundation for any config knob that accepts a
 * list of strings (blocked items, whitelist, blacklist).  These tests pin the
 * three cases: absent key → fallback without warning; valid list → values
 * returned; wrong type → fallback with exactly one warning.</p>
 *
 * <p>Construction mirrors {@link MentalConfigTest}: parse inline YAML into a
 * real {@link YamlConfiguration} and hand a section plus fresh
 * {@link ConfigIssues} to the package-private {@link ConfigReader} record.</p>
 */
class ConfigReaderTest {

    private static YamlConfiguration yaml(String content) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(content);
        return cfg;
    }

    /** Returns a ConfigReader wrapping the named sub-section, or the root if null. */
    private static ConfigReader reader(YamlConfiguration cfg, String section, ConfigIssues issues) {
        return new ConfigReader(
                section == null ? cfg : cfg.getConfigurationSection(section),
                section == null ? "test" : "test: " + section,
                issues);
    }

    /* ------------------------------------------------------------------ */
    /*  stringList — missing key → fallback, no warning                    */
    /* ------------------------------------------------------------------ */

    @Test
    void stringList_missingKey_returnsFallbackWithoutWarning() throws Exception {
        YamlConfiguration cfg = yaml("other: value");
        ConfigIssues issues = new ConfigIssues();
        ConfigReader reader = reader(cfg, null, issues);

        List<String> result = reader.stringList("items", List.of("SWORD"));

        assertEquals(List.of("SWORD"), result);
        assertTrue(issues.clean(), "no warning expected for absent key");
    }

    /* ------------------------------------------------------------------ */
    /*  stringList — null section (missing config file) → fallback         */
    /* ------------------------------------------------------------------ */

    @Test
    void stringList_nullSection_returnsFallbackWithoutWarning() {
        ConfigIssues issues = new ConfigIssues();
        ConfigReader reader = new ConfigReader(null, "test", issues);

        List<String> result = reader.stringList("items", List.of("SHIELD"));

        assertEquals(List.of("SHIELD"), result);
        assertTrue(issues.clean(), "no warning expected for null section");
    }

    /* ------------------------------------------------------------------ */
    /*  stringList — valid list → returns values                           */
    /* ------------------------------------------------------------------ */

    @Test
    void stringList_validList_returnsValues() throws Exception {
        YamlConfiguration cfg = yaml("section:\n  items:\n    - SHIELD\n    - CROSSBOW\n");
        ConfigIssues issues = new ConfigIssues();
        ConfigReader reader = reader(cfg, "section", issues);

        List<String> result = reader.stringList("items", List.of("SWORD"));

        assertEquals(List.of("SHIELD", "CROSSBOW"), result);
        assertTrue(issues.clean(), "no warning expected for a valid list");
    }

    /* ------------------------------------------------------------------ */
    /*  stringList — wrong type → fallback + exactly one warning            */
    /* ------------------------------------------------------------------ */

    @Test
    void stringList_wrongType_returnsFallbackAndWarns() throws Exception {
        // A scalar string rather than a YAML list.
        YamlConfiguration cfg = yaml("section:\n  items: SHIELD\n");
        ConfigIssues issues = new ConfigIssues();
        ConfigReader reader = reader(cfg, "section", issues);

        List<String> result = reader.stringList("items", List.of("DEFAULT"));

        assertEquals(List.of("DEFAULT"), result);
        List<String> warnings = issues.all();
        assertFalse(warnings.isEmpty(), "expected exactly one warning for wrong type");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("items"), "warning must mention the key");
    }

    /* ------------------------------------------------------------------ */
    /*  stringList — empty list is a valid list (not wrong type)           */
    /* ------------------------------------------------------------------ */

    @Test
    void stringList_emptyList_returnsEmptyWithoutWarning() throws Exception {
        YamlConfiguration cfg = yaml("section:\n  items: []\n");
        ConfigIssues issues = new ConfigIssues();
        ConfigReader reader = reader(cfg, "section", issues);

        List<String> result = reader.stringList("items", List.of("SHIELD"));

        assertTrue(result.isEmpty());
        assertTrue(issues.clean(), "an empty list is still a valid list");
    }
}
