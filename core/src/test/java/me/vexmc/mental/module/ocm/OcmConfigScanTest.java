package me.vexmc.mental.module.ocm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * The API-less fallback must read OCM's modeset config the way OCM does:
 * disabled wins, always-enabled wins next, and membership in any modeset
 * reachable through the worlds section is a conservative yes.
 */
class OcmConfigScanTest {

    @Test
    void ocmDefaultConfigYieldsEveryOverlappingMechanic() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        // The shape OCM 2.x ships: fishing + projectiles always on, knockback
        // and damage modules inside the default "old" modeset.
        yaml.loadFromString(
                """
                always_enabled_modules:
                  - "attack-frequency"
                  - "old-fishing-knockback"
                  - "fishing-rod-velocity"
                  - "projectile-knockback"
                disabled_modules:
                  - "disable-offhand"
                modesets:
                  old:
                    - "disable-attack-cooldown"
                    - "old-tool-damage"
                    - "old-player-knockback"
                    - "old-critical-hits"
                  new:
                    - "old-golden-apples"
                worlds:
                  __default__: [ "old", "new" ]
                """);

        Set<OcmMechanic> verdicts = OcmConfigScan.verdicts(yaml);

        assertEquals(Set.copyOf(java.util.EnumSet.allOf(OcmMechanic.class)), Set.copyOf(verdicts));
    }

    @Test
    void disabledModulesAndUnreachableModesetsAreNotHandled() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(
                """
                always_enabled_modules:
                  - "old-fishing-knockback"
                disabled_modules:
                  - "projectile-knockback"
                  - "fishing-rod-velocity"
                modesets:
                  old:
                    - "old-player-knockback"
                  arena:
                    - "old-tool-damage"
                worlds:
                  __default__: [ "old" ]
                """);

        Set<OcmMechanic> verdicts = OcmConfigScan.verdicts(yaml);

        assertTrue(verdicts.contains(OcmMechanic.FISHING_KNOCKBACK), "always-enabled is handled");
        assertTrue(verdicts.contains(OcmMechanic.MELEE_KNOCKBACK), "reachable modeset is handled");
        assertFalse(verdicts.contains(OcmMechanic.PROJECTILE_KNOCKBACK), "disabled wins");
        assertFalse(verdicts.contains(OcmMechanic.FISHING_ROD_VELOCITY), "disabled wins");
        assertFalse(verdicts.contains(OcmMechanic.TOOL_DAMAGE),
                "a modeset no world allows cannot reach players");
        assertFalse(verdicts.contains(OcmMechanic.CRITICAL_HITS), "never mentioned, never handled");
    }

    @Test
    void missingWorldsSectionReachesEveryModeset() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(
                """
                modesets:
                  anything:
                    - "old-player-knockback"
                """);

        assertTrue(OcmConfigScan.verdicts(yaml).contains(OcmMechanic.MELEE_KNOCKBACK));
    }

    @Test
    void legacyPerModuleFlagsAreHonoredWhenNoModesetStructureExists() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(
                """
                old-player-knockback:
                  enabled: true
                  knockback-horizontal: 0.4
                old-fishing-knockback:
                  enabled: false
                """);

        Set<OcmMechanic> verdicts = OcmConfigScan.verdicts(yaml);

        assertTrue(verdicts.contains(OcmMechanic.MELEE_KNOCKBACK));
        assertFalse(verdicts.contains(OcmMechanic.FISHING_KNOCKBACK));
        assertFalse(verdicts.contains(OcmMechanic.TOOL_DAMAGE));
    }

    @Test
    void emptyConfigHandlesNothing() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("");
        assertTrue(OcmConfigScan.verdicts(yaml).isEmpty());
    }
}
