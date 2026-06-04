package me.vexmc.mental.module.knockback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.vexmc.mental.config.ConfigSources;
import me.vexmc.mental.config.MentalConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class KnockbackProfilesTest {

    private static MentalConfig configWithProfiles(String... names) throws Exception {
        MentalConfig config = new MentalConfig();
        Map<String, org.bukkit.configuration.ConfigurationSection> profiles =
                new java.util.TreeMap<>();
        for (String name : names) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString("display-name: " + name + "\n");
            profiles.put(name, yaml);
        }
        YamlConfiguration empty = new YamlConfiguration();
        config.reload(new ConfigSources(empty, empty, empty, empty, profiles));
        return config;
    }

    @Test
    void overridesValidateAgainstLoadedProfilesAndNormalize() throws Exception {
        KnockbackProfiles profiles = new KnockbackProfiles(configWithProfiles("kohi"));
        UUID player = UUID.randomUUID();

        assertNull(profiles.override(player));
        assertFalse(profiles.setOverrideName(player, "minemen-exact"));
        assertNull(profiles.override(player));

        assertTrue(profiles.setOverrideName(player, "  KOHI "));
        assertEquals("kohi", profiles.override(player));

        assertTrue(profiles.setOverrideName(player, null));
        assertNull(profiles.override(player));
    }

    @Test
    void staleOverridesClearWhenTheirProfileVanishes() throws Exception {
        MentalConfig config = configWithProfiles("kohi");
        KnockbackProfiles profiles = new KnockbackProfiles(config);
        UUID kept = UUID.randomUUID();
        UUID dropped = UUID.randomUUID();
        assertTrue(profiles.setOverrideName(kept, "legacy-1.7")); // the built-in always exists
        assertTrue(profiles.setOverrideName(dropped, "kohi"));

        // A reload without the kohi file: its override goes stale.
        YamlConfiguration empty = new YamlConfiguration();
        config.reload(new ConfigSources(empty, empty, empty, empty, Map.of()));
        List<UUID> stale = profiles.clearStaleOverrides();

        assertEquals(List.of(dropped), stale);
        assertNull(profiles.override(dropped));
        assertEquals("legacy-1.7", profiles.override(kept));
    }

    @Test
    void forgetAndClearDropOverrides() throws Exception {
        KnockbackProfiles profiles = new KnockbackProfiles(configWithProfiles("kohi"));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertTrue(profiles.setOverrideName(first, "kohi"));
        assertTrue(profiles.setOverrideName(second, "kohi"));

        profiles.forget(first);
        assertNull(profiles.override(first));
        assertEquals("kohi", profiles.override(second));

        profiles.clear();
        assertNull(profiles.override(second));
    }
}
