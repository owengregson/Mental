package me.vexmc.mental.module.knockback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.TreeMap;
import me.vexmc.mental.config.ConfigSources;
import me.vexmc.mental.config.MentalConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Knockback is global: {@link KnockbackProfiles} resolves the server-wide
 * default (and the optional per-world map), with no per-player assignment. The
 * default tracks the {@code knockback.profile} key, normalised and validated by
 * the config parser. (The {@code resolve(victim)} world→default path needs a
 * live entity and is exercised by the ProfileSuite / EraParitySuite live tests.)
 */
class KnockbackProfilesTest {

    private static MentalConfig configWith(String selected, String... profileNames) throws Exception {
        MentalConfig config = new MentalConfig();
        Map<String, ConfigurationSection> profiles = new TreeMap<>();
        for (String name : profileNames) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString("display-name: " + name + "\n");
            profiles.put(name, yaml);
        }
        YamlConfiguration empty = new YamlConfiguration();
        YamlConfiguration knockback = new YamlConfiguration();
        if (selected != null) {
            knockback.loadFromString("knockback:\n  profile: " + selected + "\n");
        }
        config.reload(new ConfigSources(empty, knockback, empty, empty, profiles));
        return config;
    }

    @Test
    void defaultProfileNameReflectsTheConfigSelection() throws Exception {
        // No selection → the built-in legacy-1.7 default.
        assertEquals("legacy-1.7",
                new KnockbackProfiles(configWith(null, "kohi")).defaultProfileName());
        // An explicit, loaded selection wins.
        assertEquals("kohi",
                new KnockbackProfiles(configWith("kohi", "kohi")).defaultProfileName());
    }

    @Test
    void anUnknownSelectionFallsBackToTheBuiltInDefault() throws Exception {
        assertEquals("legacy-1.7",
                new KnockbackProfiles(configWith("minemen-exact")).defaultProfileName());
    }

    @Test
    void namesAndAllExposeEveryLoadedProfileIncludingTheBuiltIn() throws Exception {
        KnockbackProfiles profiles = new KnockbackProfiles(configWith(null, "kohi", "mmc"));
        assertTrue(profiles.names().contains("kohi"));
        assertTrue(profiles.names().contains("mmc"));
        assertTrue(profiles.names().contains("legacy-1.7")); // the built-in is always present
        assertTrue(profiles.all().containsKey("kohi"));
    }
}
