package me.vexmc.mental.v5.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Default extraction and superseded-preset upgrade, ported from the retired
 * config.ConfigStoreTest (File → Path): extract once and never overwrite,
 * complete pre-1.4.0 files then upgrade untouched superseded revisions in place,
 * freeze owner edits, and never throw on a missing resource.
 */
class ConfigStoreTest {

    @TempDir
    Path dataFolder;

    private final List<String> logged = new ArrayList<>();

    /** The real bundled resources, exactly as the plugin jar serves them. */
    private final Function<String, InputStream> resources = name ->
            getClass().getClassLoader().getResourceAsStream(name);

    private ConfigStore store() {
        return new ConfigStore(dataFolder, resources, logged::add);
    }

    private Path profile(String name) {
        return dataFolder.resolve(ConfigStore.PROFILES_DIR).resolve(name + ".yml");
    }

    @Test
    void ensureDefaultFilesExtractsEverythingOnceAndNeverOverwrites() throws Exception {
        ConfigStore store = store();
        store.ensureDefaultFiles();

        for (String file : List.of(ConfigStore.MAIN_FILE, ConfigStore.KNOCKBACK_FILE,
                ConfigStore.HIT_REG_FILE, ConfigStore.LATENCY_FILE)) {
            assertTrue(Files.isRegularFile(dataFolder.resolve(file)), file + " not extracted");
        }
        for (String preset : ConfigStore.BUNDLED_PROFILES) {
            assertTrue(Files.isRegularFile(profile(preset)), preset + " preset not extracted");
        }

        // An owner edit survives; a deleted preset regenerates.
        Files.writeString(profile("kohi"), "display-name: Mine\n", StandardCharsets.UTF_8);
        Files.delete(profile("lunar"));

        store.ensureDefaultFiles();

        String patchedKohi = Files.readString(profile("kohi"), StandardCharsets.UTF_8);
        assertTrue(patchedKohi.startsWith("display-name: Mine"), "owner edit must survive: " + patchedKohi);
        assertTrue(patchedKohi.contains("delivery:") && patchedKohi.contains("melee: tracker"),
                "missing delivery section must be added: " + patchedKohi);
        assertTrue(Files.isRegularFile(profile("lunar")), "deleted preset must regenerate");

        // Idempotent: a third pass changes nothing.
        store.ensureDefaultFiles();
        assertEquals(patchedKohi, Files.readString(profile("kohi"), StandardCharsets.UTF_8));
    }

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
                # Explicit like the real v1.3.0 bundle (git-verified): since the
                # 2.4.8 owner floor moved the LEGACY_17 parse fallback to 0.0,
                # relying on the fallback here would no longer parse to the
                # MMC_1_3 revision's -3.9.
                vertical-min: -3.9
              range-reduction:
                enabled: true
              modifiers:
                combos: false
                armor-resistance: legacy
            """;

    @Test
    void unTunedSupersededPresetIsUpgradedInPlace() throws Exception {
        ConfigStore store = store();
        Files.createDirectories(profile("mmc").getParent());
        Files.writeString(profile("mmc"), MMC_1_3_BODY, StandardCharsets.UTF_8);

        store.ensureDefaultFiles();

        YamlConfiguration upgraded = YamlConfiguration.loadConfiguration(profile("mmc").toFile());
        assertEquals(0.32, upgraded.getDouble("knockback.base.horizontal"),
                "superseded mmc must carry the archived dev123 values");
        assertEquals("add", upgraded.getString("knockback.vertical-mode"));
        assertTrue(logged.stream().anyMatch(line -> line.contains("mmc.yml") && line.contains("upgraded")),
                () -> "expected an upgrade report, logged: " + logged);

        String afterFirst = Files.readString(profile("mmc"), StandardCharsets.UTF_8);
        store.ensureDefaultFiles();
        assertEquals(afterFirst, Files.readString(profile("mmc"), StandardCharsets.UTF_8));
    }

    private static final String SIGNATURE_2_2_0_BODY = """
            display-name: Signature
            description: "Mental's signature feel — velt's residual wipe and pinned 0.36 vertical, with airborne combo hits trimmed 8% to hold the reach pocket."
            knockback:
              base:
                horizontal: 0.325
                vertical: 0.36
              vertical-mode: add
              extra:
                horizontal: 0.5
                vertical: 0.0
              wtap-extra:
                enabled: false
                horizontal: 0.5
                vertical: 0.0
              friction:
                x: 0.1
                y: 0.1
                z: 0.1
              limits:
                vertical: 0.36
                vertical-min: -3.9
                horizontal: -1
              air:
                horizontal: 0.92
                vertical: 1.0
              delivery:
                melee: tracker
                projectile: tracker
              modifiers:
                sprint: 1.0
                combos: false
                armor-resistance: none
                shield-blocking-cancels: true
            """;

    @Test
    void unTunedSignaturePresetUpgradesToTheVerticalTuning() throws Exception {
        ConfigStore store = store();
        Files.createDirectories(profile("signature").getParent());
        Files.writeString(profile("signature"), SIGNATURE_2_2_0_BODY, StandardCharsets.UTF_8);

        store.ensureDefaultFiles();

        YamlConfiguration upgraded = YamlConfiguration.loadConfiguration(profile("signature").toFile());
        assertEquals(0.365, upgraded.getDouble("knockback.base.vertical"),
                "the upgrade must carry the 2.2.1 base-vertical lift");
        assertEquals(0.98, upgraded.getDouble("knockback.air.vertical"),
                "the upgrade must carry the 2.2.1 airborne vertical trim");
        assertEquals(0.92, upgraded.getDouble("knockback.air.horizontal"),
                "the horizontal pocket trim is unchanged");
        assertTrue(logged.stream().anyMatch(line ->
                line.contains("signature.yml") && line.contains("upgraded")),
                () -> "expected an upgrade report, logged: " + logged);

        String afterFirst = Files.readString(profile("signature"), StandardCharsets.UTF_8);
        store.ensureDefaultFiles();
        assertEquals(afterFirst, Files.readString(profile("signature"), StandardCharsets.UTF_8));
    }

    @Test
    void tunedSignaturePresetIsNeverTouched() throws Exception {
        ConfigStore store = store();
        Files.createDirectories(profile("signature").getParent());
        String tuned = SIGNATURE_2_2_0_BODY.replace("horizontal: 0.92", "horizontal: 0.95");
        Files.writeString(profile("signature"), tuned, StandardCharsets.UTF_8);

        store.ensureDefaultFiles();

        YamlConfiguration kept = YamlConfiguration.loadConfiguration(profile("signature").toFile());
        assertEquals(0.95, kept.getDouble("knockback.air.horizontal"),
                "owner-tuned values must survive every upgrade pass");
        assertEquals(1.0, kept.getDouble("knockback.air.vertical"), "an owner-tuned file is never upgraded");
        assertFalse(logged.stream().anyMatch(line ->
                line.contains("signature.yml") && line.contains("upgraded")),
                () -> "no upgrade may be reported for a tuned file, logged: " + logged);
    }

    @Test
    void tunedSupersededPresetIsNeverTouched() throws Exception {
        ConfigStore store = store();
        Files.createDirectories(profile("mmc").getParent());
        String tuned = MMC_1_3_BODY.replace("horizontal: 0.38488", "horizontal: 0.42");
        Files.writeString(profile("mmc"), tuned, StandardCharsets.UTF_8);

        store.ensureDefaultFiles();

        YamlConfiguration kept = YamlConfiguration.loadConfiguration(profile("mmc").toFile());
        assertEquals(0.42, kept.getDouble("knockback.base.horizontal"),
                "owner-tuned values must survive every upgrade pass");
        assertEquals("set", kept.getString("knockback.vertical-mode"));
        assertFalse(logged.stream().anyMatch(line ->
                line.contains("mmc.yml") && line.contains("upgraded")),
                () -> "no upgrade may be reported for a tuned file, logged: " + logged);
    }

    /**
     * A kohi file exactly as 2.4.6 shipped it (vertical-min −3.9, every other
     * value the current archive) — parses to the KOHI_1_8 superseded revision.
     * Keys omitted here fall back to LEGACY_17 values, which for kohi equal the
     * constant's (friction 0.5, air 1.0, sprint 1.0, combos true, tracker wire).
     */
    private static final String KOHI_2_4_6_BODY = """
            display-name: Kohi
            description: "The canonical Kohi/HCF values — lower base, smaller per-level bonus (0.425/0.085), 1.7.10 ledger combos."
            knockback:
              base:
                horizontal: 0.35
                vertical: 0.35
              extra:
                horizontal: 0.425
                vertical: 0.085
              wtap-extra:
                enabled: false
                horizontal: 0.425
                vertical: 0.085
              limits:
                vertical: 0.4
                vertical-min: -3.9
                horizontal: -1
              delivery:
                melee: tracker
                projectile: tracker
              modifiers:
                combos: true
                armor-resistance: none
            """;

    @Test
    void unTunedPreFloorKohiUpgradesToThePracticeFloor() throws Exception {
        ConfigStore store = store();
        Files.createDirectories(profile("kohi").getParent());
        Files.writeString(profile("kohi"), KOHI_2_4_6_BODY, StandardCharsets.UTF_8);

        store.ensureDefaultFiles();

        YamlConfiguration upgraded = YamlConfiguration.loadConfiguration(profile("kohi").toFile());
        assertEquals(0.0, upgraded.getDouble("knockback.limits.vertical-min"),
                "an unedited pre-floor kohi must gain the 2.4.7 practice floor");
        assertEquals(0.35, upgraded.getDouble("knockback.base.horizontal"),
                "the archived kohi values are untouched by the floor upgrade");
        assertTrue(logged.stream().anyMatch(line ->
                line.contains("kohi.yml") && line.contains("upgraded")),
                () -> "expected an upgrade report, logged: " + logged);

        // Idempotent: the upgraded file IS the current bundle — never re-flagged.
        String afterFirst = Files.readString(profile("kohi"), StandardCharsets.UTF_8);
        store.ensureDefaultFiles();
        assertEquals(afterFirst, Files.readString(profile("kohi"), StandardCharsets.UTF_8));
    }

    @Test
    void tunedPreFloorKohiKeepsItsOldFloorForever() throws Exception {
        ConfigStore store = store();
        Files.createDirectories(profile("kohi").getParent());
        String tuned = KOHI_2_4_6_BODY.replace("horizontal: 0.35", "horizontal: 0.37");
        Files.writeString(profile("kohi"), tuned, StandardCharsets.UTF_8);

        store.ensureDefaultFiles();

        YamlConfiguration kept = YamlConfiguration.loadConfiguration(profile("kohi").toFile());
        assertEquals(0.37, kept.getDouble("knockback.base.horizontal"),
                "owner-tuned values must survive every upgrade pass");
        assertEquals(-3.9, kept.getDouble("knockback.limits.vertical-min"),
                "a tuned file keeps its own floor — frozen forever");
        assertFalse(logged.stream().anyMatch(line ->
                line.contains("kohi.yml") && line.contains("upgraded")),
                () -> "no upgrade may be reported for a tuned file, logged: " + logged);
    }

    /**
     * A legacy-1.7 file exactly as 2.4.7 shipped it (vertical-min −3.9, every
     * other value the era default). Keys omitted here fall back to LEGACY_17
     * values, which for legacy-1.7 equal the constant's by definition — only
     * the pre-floor vertical-min differs from the current bundle.
     */
    private static final String LEGACY17_2_4_7_BODY = """
            display-name: Legacy 1.7
            description: "The 1.7.10 combat model: vanilla-era values with ledger combos."
            knockback:
              limits:
                vertical: 0.4
                vertical-min: -3.9
                horizontal: -1
            """;

    @Test
    void unTunedPreFloorLegacyUpgradesToTheOwnerFloor() throws Exception {
        ConfigStore store = store();
        Files.createDirectories(profile("legacy-1.7").getParent());
        Files.writeString(profile("legacy-1.7"), LEGACY17_2_4_7_BODY, StandardCharsets.UTF_8);

        store.ensureDefaultFiles();

        YamlConfiguration upgraded =
                YamlConfiguration.loadConfiguration(profile("legacy-1.7").toFile());
        assertEquals(0.0, upgraded.getDouble("knockback.limits.vertical-min"),
                "an unedited pre-floor legacy-1.7 must gain the 2.4.8 owner floor");
        assertEquals(0.4, upgraded.getDouble("knockback.base.horizontal"),
                "the era values are untouched by the floor upgrade");
        assertTrue(logged.stream().anyMatch(line ->
                line.contains("legacy-1.7.yml") && line.contains("upgraded")),
                () -> "expected an upgrade report, logged: " + logged);

        // Idempotent: the upgraded file IS the current bundle — never re-flagged.
        String afterFirst = Files.readString(profile("legacy-1.7"), StandardCharsets.UTF_8);
        store.ensureDefaultFiles();
        assertEquals(afterFirst, Files.readString(profile("legacy-1.7"), StandardCharsets.UTF_8));
    }

    @Test
    void tunedPreFloorLegacyKeepsItsOldFloorForever() throws Exception {
        ConfigStore store = store();
        Files.createDirectories(profile("legacy-1.7").getParent());
        String tuned = LEGACY17_2_4_7_BODY.replace("vertical: 0.4", "vertical: 0.45");
        Files.writeString(profile("legacy-1.7"), tuned, StandardCharsets.UTF_8);

        store.ensureDefaultFiles();

        YamlConfiguration kept =
                YamlConfiguration.loadConfiguration(profile("legacy-1.7").toFile());
        assertEquals(0.45, kept.getDouble("knockback.limits.vertical"),
                "owner-tuned values must survive every upgrade pass");
        assertEquals(-3.9, kept.getDouble("knockback.limits.vertical-min"),
                "a tuned file keeps its own floor — frozen forever");
        assertFalse(logged.stream().anyMatch(line ->
                line.contains("legacy-1.7.yml") && line.contains("upgraded")),
                () -> "no upgrade may be reported for a tuned file, logged: " + logged);
    }

    @Test
    void loadSourcesTreatsUnparseableFilesAsEmptyAndReportsThem() throws Exception {
        ConfigStore store = store();
        store.ensureDefaultFiles();
        Files.writeString(dataFolder.resolve(ConfigStore.HIT_REG_FILE),
                "hit-registration: [unclosed", StandardCharsets.UTF_8);

        ConfigStore.Sources sources = store.loadSources();

        assertEquals(ConfigStore.BUNDLED_PROFILES.size(), sources.profiles().size());
        assertFalse(sources.hitReg().isConfigurationSection("hit-registration"));
        assertTrue(logged.stream().anyMatch(line -> line.contains(ConfigStore.HIT_REG_FILE)),
                () -> "expected a parse report, logged: " + logged);
    }

    @Test
    void missingResourceIsReportedNotThrown() {
        ConfigStore broken = new ConfigStore(dataFolder,
                name -> name.equals(ConfigStore.KNOCKBACK_FILE)
                        ? null
                        : new ByteArrayInputStream("a: 1\n".getBytes(StandardCharsets.UTF_8)),
                logged::add);

        broken.ensureDefaultFiles();

        assertTrue(logged.stream().anyMatch(line -> line.contains(ConfigStore.KNOCKBACK_FILE)));
    }
}
