package me.vexmc.mental.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import me.vexmc.mental.common.debug.DebugCategory;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class MentalConfigTest {

    @Test
    void emptySourceYieldsAllDefaultsWithoutWarnings() throws Exception {
        MentalConfig config = new MentalConfig();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("");

        List<String> warnings = config.reload(yaml);

        assertTrue(warnings.isEmpty(), () -> "unexpected warnings: " + warnings);
        assertEquals(HitRegSettings.DEFAULTS, config.hitReg());
        assertEquals(KnockbackSettings.DEFAULTS, config.knockback());
        assertEquals(CompensationSettings.DEFAULTS, config.compensation());
        assertEquals(FishingKnockbackSettings.DEFAULTS, config.fishingKnockback());
        assertEquals(RodVelocitySettings.DEFAULTS, config.rodVelocity());
        assertEquals(ProjectileKnockbackSettings.DEFAULTS, config.projectileKnockback());
        assertEquals(AnticheatSettings.DEFAULTS, config.anticheat());
        assertEquals(CompatibilitySettings.DEFAULTS, config.compatibility());
        assertEquals(DebugSettings.DEFAULTS, config.debug());
    }

    @Test
    void bundledDefaultFileMatchesRecordDefaults() throws Exception {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("config.yml"), StandardCharsets.UTF_8));
        MentalConfig config = new MentalConfig();

        List<String> warnings = config.reload(yaml);

        assertTrue(warnings.isEmpty(), () -> "unexpected warnings: " + warnings);
        assertEquals(HitRegSettings.DEFAULTS, config.hitReg());
        assertEquals(KnockbackSettings.DEFAULTS, config.knockback());
        assertEquals(CompensationSettings.DEFAULTS, config.compensation());
        assertEquals(FishingKnockbackSettings.DEFAULTS, config.fishingKnockback());
        assertEquals(RodVelocitySettings.DEFAULTS, config.rodVelocity());
        assertEquals(ProjectileKnockbackSettings.DEFAULTS, config.projectileKnockback());
        assertEquals(AnticheatSettings.DEFAULTS, config.anticheat());
        assertEquals(CompatibilitySettings.DEFAULTS, config.compatibility());
        assertEquals(DebugSettings.DEFAULTS, config.debug());
    }

    @Test
    void compatibilitySectionParsesCoordinationModes() throws Exception {
        MentalConfig config = new MentalConfig();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(
                """
                compatibility:
                  old-combat-mechanics: ignore
                """);

        assertTrue(config.reload(yaml).isEmpty());
        assertEquals(OcmCoordination.IGNORE, config.compatibility().oldCombatMechanics());
    }

    @Test
    void invalidValuesWarnAndFallBack() throws Exception {
        MentalConfig config = new MentalConfig();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(
                """
                modules:
                  hit-registration:
                    max-cps: -5
                    fast-path:
                      enabled: "definitely"
                  knockback:
                    base:
                      horizontal: -3.0
                  latency-compensation:
                    probe-strategy: carrier-pigeon
                """);

        List<String> warnings = config.reload(yaml);

        assertEquals(20, config.hitReg().maxCps());
        assertTrue(config.hitReg().fastPath());
        assertEquals(0.4, config.knockback().baseHorizontal());
        assertEquals(ProbeStrategy.PING, config.compensation().probeStrategy());
        assertEquals(4, warnings.size(), () -> "warnings: " + warnings);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("modules.hit-registration.max-cps")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("modules.latency-compensation.probe-strategy")));
    }

    @Test
    void parsesCustomValuesIncludingDebugCategories() throws Exception {
        MentalConfig config = new MentalConfig();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(
                """
                modules:
                  knockback:
                    enabled: false
                    extra:
                      horizontal: 0.6
                    limits:
                      horizontal: 1.2
                    modifiers:
                      sprint: 2
                      combos: false
                      armor-resistance: scaling
                  latency-compensation:
                    probe-strategy: keepalive
                    ping-offset-ms: 10
                  fishing-knockback:
                    reel-in: cancel
                anticheat:
                  mode: force-safe
                  known: [GrimAC, Vulcan, Matrix]
                debug:
                  enabled: true
                  categories:
                    hitreg: true
                    packets: true
                    knockback: false
                """);

        List<String> warnings = config.reload(yaml);

        assertTrue(warnings.isEmpty(), () -> "unexpected warnings: " + warnings);
        assertEquals(false, config.knockback().enabled());
        assertEquals(0.6, config.knockback().extraHorizontal());
        assertEquals(1.2, config.knockback().limitHorizontal());
        assertTrue(config.knockback().limitsHorizontal());
        assertEquals(2.0, config.knockback().sprintFactor());
        assertFalse(config.knockback().combos());
        assertEquals(ResistancePolicy.SCALING, config.knockback().resistance());
        assertEquals(ProbeStrategy.KEEPALIVE, config.compensation().probeStrategy());
        assertEquals(10, config.compensation().pingOffsetMillis());
        assertEquals(ReelInPolicy.CANCEL, config.fishingKnockback().reelIn());
        assertEquals(AnticheatMode.FORCE_SAFE, config.anticheat().mode());
        assertEquals(List.of("GrimAC", "Vulcan", "Matrix"), config.anticheat().knownPlugins());
        assertTrue(config.debug().enabled());
        assertEquals(Set.of(DebugCategory.HITREG, DebugCategory.PACKETS), config.debug().categories());
    }

    @Test
    void feedbackIntervalParsesAutoFixedAndLegacyValues() throws Exception {
        MentalConfig config = new MentalConfig();
        YamlConfiguration yaml = new YamlConfiguration();

        yaml.loadFromString(
                """
                modules:
                  hit-registration:
                    fast-path:
                      feedback-min-interval-ms: auto
                """);
        assertTrue(config.reload(yaml).isEmpty());
        assertEquals(HitRegSettings.FEEDBACK_INTERVAL_AUTO, config.hitReg().feedbackMinIntervalMillis());

        yaml.loadFromString(
                """
                modules:
                  hit-registration:
                    fast-path:
                      feedback-min-interval-ms: 350
                """);
        assertTrue(config.reload(yaml).isEmpty());
        assertEquals(350L, config.hitReg().feedbackMinIntervalMillis());

        // Configs written before the auto default keep their explicit number.
        yaml.loadFromString(
                """
                modules:
                  hit-registration:
                    fast-path:
                      feedback-min-interval-ms: 500
                """);
        assertTrue(config.reload(yaml).isEmpty());
        assertEquals(500L, config.hitReg().feedbackMinIntervalMillis());
    }

    @Test
    void reloadPublishesFreshAtomicSnapshot() throws Exception {
        MentalConfig config = new MentalConfig();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("");

        config.reload(yaml);
        MentalConfig.Snapshot first = config.snapshot();
        config.reload(yaml);
        MentalConfig.Snapshot second = config.snapshot();

        assertNotSame(first, second);
        assertEquals(first, second);
    }
}
