package me.vexmc.mental.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigStoreTest {

    @TempDir
    File dataFolder;

    private final List<String> logged = new ArrayList<>();

    /** The real bundled resources, exactly as the plugin jar serves them. */
    private final Function<String, InputStream> resources = name ->
            getClass().getClassLoader().getResourceAsStream(name);

    private ConfigStore store() {
        return new ConfigStore(dataFolder, resources, logged::add);
    }

    @Test
    void ensureDefaultFilesExtractsEverythingOnceAndNeverOverwrites() throws Exception {
        ConfigStore store = store();
        store.ensureDefaultFiles();

        for (String file : List.of(
                ConfigStore.KNOCKBACK_FILE, ConfigStore.HIT_REG_FILE, ConfigStore.LATENCY_FILE)) {
            assertTrue(new File(dataFolder, file).isFile(), file + " not extracted");
        }
        for (String preset : ConfigStore.BUNDLED_PROFILES) {
            assertTrue(new File(dataFolder, ConfigStore.PROFILES_DIR + "/" + preset + ".yml").isFile(),
                    preset + " preset not extracted");
        }

        // An owner edit survives re-extraction; a deleted preset regenerates.
        File kohi = new File(dataFolder, ConfigStore.PROFILES_DIR + "/kohi.yml");
        Files.writeString(kohi.toPath(), "display-name: Mine\n", StandardCharsets.UTF_8);
        File lunar = new File(dataFolder, ConfigStore.PROFILES_DIR + "/lunar.yml");
        assertTrue(lunar.delete());

        store.ensureDefaultFiles();

        // Owner edits survive — but a pre-1.4.0 preset (no delivery block)
        // gets exactly the missing section added, once.
        String patchedKohi = Files.readString(kohi.toPath(), StandardCharsets.UTF_8);
        assertTrue(patchedKohi.startsWith("display-name: Mine"),
                "owner edit must survive: " + patchedKohi);
        assertTrue(patchedKohi.contains("delivery:")
                        && patchedKohi.contains("melee: tracker"),
                "missing delivery section must be added: " + patchedKohi);
        assertTrue(lunar.isFile(), "deleted preset must regenerate");

        // Idempotent: a third pass changes nothing.
        store.ensureDefaultFiles();
        assertEquals(patchedKohi, Files.readString(kohi.toPath(), StandardCharsets.UTF_8));
    }

    /**
     * The old bundled mmc body, values-complete: what a 1.3.0–1.7.0 install
     * carries when the owner never tuned it (comments stripped — the
     * upgrade matches parsed values, not bytes).
     */
    private static final String MMC_1_3_BODY = """
            display-name: MMC
            description: "Community remake of the Minemen Club feel: assigned vertical, distance taper, flat delivery."
            knockback:
              base:
                horizontal: 0.38488
                vertical: 0.25635
              vertical-mode: set
              extra:
                horizontal: 0.5
                vertical: 0.1
              friction:
                x: 0.5248
                y: 0.5248
                z: 0.5248
              limits:
                vertical: 4.0
              range-reduction:
                enabled: true
              modifiers:
                combos: false
                armor-resistance: legacy
            """;

    @Test
    void unTunedSupersededPresetIsUpgradedInPlace() throws Exception {
        ConfigStore store = store();
        File mmc = new File(dataFolder, ConfigStore.PROFILES_DIR + "/mmc.yml");
        assertTrue(mmc.getParentFile().mkdirs());
        // A pre-1.4.0 install: old values, no delivery block. The chain must
        // first complete it to its old canonical form (delivery
        // immediate/immediate), then recognize and upgrade it.
        Files.writeString(mmc.toPath(), MMC_1_3_BODY, StandardCharsets.UTF_8);

        store.ensureDefaultFiles();

        YamlConfiguration upgraded = YamlConfiguration.loadConfiguration(mmc);
        assertEquals(0.32, upgraded.getDouble("knockback.base.horizontal"),
                "superseded mmc must carry the archived dev123 values");
        assertEquals("add", upgraded.getString("knockback.vertical-mode"));
        assertTrue(logged.stream().anyMatch(line ->
                        line.contains("mmc.yml") && line.contains("upgraded")),
                () -> "expected an upgrade report, logged: " + logged);

        // Idempotent: the corrected file no longer matches any superseded
        // revision, so a second pass leaves it byte-identical.
        String afterFirst = Files.readString(mmc.toPath(), StandardCharsets.UTF_8);
        store.ensureDefaultFiles();
        assertEquals(afterFirst, Files.readString(mmc.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    void tunedSupersededPresetIsNeverTouched() throws Exception {
        ConfigStore store = store();
        File mmc = new File(dataFolder, ConfigStore.PROFILES_DIR + "/mmc.yml");
        assertTrue(mmc.getParentFile().mkdirs());
        // One tuned value (base.horizontal 0.38488 → 0.42) = an owner edit.
        String tuned = MMC_1_3_BODY.replace("horizontal: 0.38488", "horizontal: 0.42");
        Files.writeString(mmc.toPath(), tuned, StandardCharsets.UTF_8);

        store.ensureDefaultFiles();

        YamlConfiguration kept = YamlConfiguration.loadConfiguration(mmc);
        assertEquals(0.42, kept.getDouble("knockback.base.horizontal"),
                "owner-tuned values must survive every upgrade pass");
        assertEquals("set", kept.getString("knockback.vertical-mode"));
        assertFalse(logged.stream().anyMatch(line ->
                        line.contains("mmc.yml") && line.contains("upgraded")),
                () -> "no upgrade may be reported for a tuned file, logged: " + logged);
    }

    @Test
    void loadSourcesTreatsUnparseableFilesAsEmptyAndReportsThem() throws Exception {
        ConfigStore store = store();
        store.ensureDefaultFiles();
        Files.writeString(new File(dataFolder, ConfigStore.HIT_REG_FILE).toPath(),
                "hit-registration: [unclosed", StandardCharsets.UTF_8);

        ConfigSources sources = store.loadSources(new YamlConfiguration());

        assertEquals(ConfigStore.BUNDLED_PROFILES.size(), sources.profiles().size());
        assertFalse(sources.hitReg().isConfigurationSection("hit-registration"));
        assertTrue(logged.stream().anyMatch(line -> line.contains(ConfigStore.HIT_REG_FILE)),
                () -> "expected a parse report, logged: " + logged);
    }

    @Test
    void legacyLayoutIsDetectedByModuleSections() throws Exception {
        YamlConfiguration v1 = new YamlConfiguration();
        v1.loadFromString("""
                modules:
                  knockback:
                    enabled: true
                """);
        assertTrue(ConfigStore.isLegacyLayout(v1));

        YamlConfiguration v2 = new YamlConfiguration();
        v2.loadFromString("""
                modules:
                  knockback: true
                """);
        assertFalse(ConfigStore.isLegacyLayout(v2));
        assertFalse(ConfigStore.isLegacyLayout(new YamlConfiguration()));
    }

    @Test
    void migrationCarriesTunedValuesIntoTheSplitLayout() throws Exception {
        String v1 = """
                config-version: 1
                modules:
                  hit-registration:
                    enabled: true
                    max-cps: 16
                    fast-path:
                      enabled: true
                      simulate-crits: false
                  knockback:
                    enabled: true
                    base:
                      horizontal: 0.42
                      vertical: 0.36
                    modifiers:
                      sprint: 1.5
                      combos: false
                  latency-compensation:
                    enabled: false
                    ping-offset-ms: 40
                  fishing-knockback:
                    enabled: true
                    reel-in: cancel
                anticheat:
                  mode: force-safe
                  known: [GrimAC]
                debug:
                  enabled: true
                """;
        File mainFile = new File(dataFolder, ConfigStore.MAIN_FILE);
        Files.writeString(mainFile.toPath(), v1, StandardCharsets.UTF_8);
        YamlConfiguration oldMain = new YamlConfiguration();
        oldMain.loadFromString(v1);

        ConfigStore store = store();
        assertTrue(store.migrateLegacyLayout(oldMain));
        store.ensureDefaultFiles();

        assertTrue(new File(dataFolder, ConfigStore.V1_BACKUP_FILE).isFile());

        YamlConfiguration newMain = YamlConfiguration.loadConfiguration(mainFile);
        assertFalse(ConfigStore.isLegacyLayout(newMain), "migrated config.yml still legacy-shaped");
        assertFalse(newMain.getBoolean("modules.latency-compensation", true));
        assertTrue(newMain.getBoolean("modules.hit-registration", false));
        assertEquals("force-safe", newMain.getString("anticheat.mode"));
        assertEquals(List.of("GrimAC"), newMain.getStringList("anticheat.known"));
        assertTrue(newMain.getBoolean("debug.enabled"));

        YamlConfiguration hitReg = YamlConfiguration.loadConfiguration(
                new File(dataFolder, ConfigStore.HIT_REG_FILE));
        assertEquals(16, hitReg.getInt("hit-registration.max-cps"));
        assertFalse(hitReg.getBoolean("hit-registration.fast-path.simulate-crits", true));

        YamlConfiguration latency = YamlConfiguration.loadConfiguration(
                new File(dataFolder, ConfigStore.LATENCY_FILE));
        assertEquals(40, latency.getInt("latency-compensation.ping-offset-ms"));

        YamlConfiguration knockback = YamlConfiguration.loadConfiguration(
                new File(dataFolder, ConfigStore.KNOCKBACK_FILE));
        assertEquals("custom", knockback.getString("knockback.profile"));
        assertEquals("cancel", knockback.getString("fishing-knockback.reel-in"));

        File customFile = new File(dataFolder, ConfigStore.PROFILES_DIR + "/custom.yml");
        assertTrue(customFile.isFile(), "tuned v1 knockback must become profiles/custom.yml");
        YamlConfiguration custom = YamlConfiguration.loadConfiguration(customFile);
        assertEquals(0.42, custom.getDouble("knockback.base.horizontal"));
        assertEquals(0.36, custom.getDouble("knockback.base.vertical"));
        assertEquals(1.5, custom.getDouble("knockback.modifiers.sprint"));
        assertFalse(custom.getBoolean("knockback.modifiers.combos", true));

        // End to end: the migrated tree parses into the tuned profile.
        MentalConfig config = new MentalConfig();
        List<String> warnings = config.reload(store.loadSources(newMain));
        assertTrue(warnings.isEmpty(), () -> "unexpected warnings: " + warnings);
        assertFalse(config.compensation().enabled());
        KnockbackProfile profile = config.knockback().profile();
        assertEquals("custom", profile.name());
        assertEquals(0.42, profile.base().horizontal());
        assertEquals(1.5, profile.sprintFactor());
        assertFalse(profile.combos());
        assertEquals(16, config.hitReg().maxCps());
        assertFalse(config.hitReg().simulateCrits());
    }

    @Test
    void migrationOfUntunedKnockbackKeepsTheLegacyProfileSelected() throws Exception {
        String v1 = """
                config-version: 1
                modules:
                  knockback:
                    enabled: true
                    base:
                      horizontal: 0.4
                      vertical: 0.4
                """;
        File mainFile = new File(dataFolder, ConfigStore.MAIN_FILE);
        Files.writeString(mainFile.toPath(), v1, StandardCharsets.UTF_8);
        YamlConfiguration oldMain = new YamlConfiguration();
        oldMain.loadFromString(v1);

        ConfigStore store = store();
        assertTrue(store.migrateLegacyLayout(oldMain));
        store.ensureDefaultFiles();

        YamlConfiguration knockback = YamlConfiguration.loadConfiguration(
                new File(dataFolder, ConfigStore.KNOCKBACK_FILE));
        assertEquals("legacy-1.7", knockback.getString("knockback.profile"));
        // The bundled custom preset (legacy values) was extracted, not a
        // migrated one.
        YamlConfiguration custom = YamlConfiguration.loadConfiguration(
                new File(dataFolder, ConfigStore.PROFILES_DIR + "/custom.yml"));
        assertEquals("Custom", custom.getString("display-name"));
    }

    @Test
    void migrationDoesNothingOnAFreshOrAlreadySplitLayout() throws Exception {
        ConfigStore store = store();
        YamlConfiguration fresh = new YamlConfiguration();
        assertFalse(store.migrateLegacyLayout(fresh));
        assertFalse(new File(dataFolder, ConfigStore.V1_BACKUP_FILE).exists());
    }

    @Test
    void missingResourceIsReportedNotThrown() {
        ConfigStore broken = new ConfigStore(dataFolder,
                name -> name.equals(ConfigStore.KNOCKBACK_FILE)
                        ? null
                        : new ByteArrayInputStream("a: 1\n".getBytes(StandardCharsets.UTF_8)),
                logged::add);

        broken.ensureDefaultFiles();

        assertNotNull(logged);
        assertTrue(logged.stream().anyMatch(line -> line.contains(ConfigStore.KNOCKBACK_FILE)));
    }
}
