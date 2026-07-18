package me.vexmc.mental.v5.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
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
 * Default extraction and superseded-preset upgrade: extract once and never
 * overwrite, complete pre-1.4.0 files then upgrade byte-identical superseded
 * revisions in place, and — the 2.4.9 byte-identity redesign — freeze ANY owner
 * edit, including one that lands on old values (the file's own "Restore -3.9"
 * invitation, the collision the old value-match reverted). The archived texts
 * are the real released bytes ({@code superseded-bundles/}, pinned per-hash by
 * {@link SupersededBundleHashTest}), not synthesised value bodies.
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
        // Bundled presets extract under their formula-category folder.
        return dataFolder.resolve(ConfigStore.PROFILES_DIR)
                .resolve(ConfigStore.bundledFolder(name)).resolve(name + ".yml");
    }

    private Path effectsPreset(String name) {
        return dataFolder.resolve(ConfigStore.EFFECTS_PRESETS_DIR).resolve(name + ".yml");
    }

    /** A UTF-8 test-classpath resource (the archived revisions and the current bundles). */
    private String resource(String classpath) {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(classpath)) {
            assertNotNull(stream, () -> "missing test resource: " + classpath);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private String archived(String name) {
        return resource("superseded-bundles/" + name);
    }

    private String currentBundle(String preset) {
        return resource("profiles/" + ConfigStore.bundledFolder(preset) + "/" + preset + ".yml");
    }

    private boolean loggedUpgrade(String presetFile) {
        return logged.stream().anyMatch(line -> line.contains(presetFile) && line.contains("upgraded"));
    }

    @Test
    void ensureDefaultFilesExtractsEverythingOnceAndNeverOverwrites() throws Exception {
        ConfigStore store = store();
        store.ensureDefaultFiles();

        for (String file : List.of(ConfigStore.MAIN_FILE, ConfigStore.KNOCKBACK_FILE,
                ConfigStore.HIT_REG_FILE, ConfigStore.LATENCY_FILE,
                ConfigStore.COMBO_FILE, ConfigStore.POTS_FILE, ConfigStore.LOADOUT_FILE,
                ConfigStore.EFFECTS_FILE)) {
            assertTrue(Files.isRegularFile(dataFolder.resolve(file)), file + " not extracted");
        }
        for (String preset : ConfigStore.BUNDLED_PROFILES) {
            assertTrue(Files.isRegularFile(profile(preset)), preset + " preset not extracted");
        }
        for (String preset : ConfigStore.BUNDLED_EFFECTS_PRESETS) {
            assertTrue(Files.isRegularFile(effectsPreset(preset)),
                    "effects preset " + preset + " not extracted");
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

    @Test
    void aPreFolderFlatFileIsKeptInPlaceNotDuplicatedIntoTheFolder() throws Exception {
        ConfigStore store = store();
        // An install that predates the formula folders: an unedited pre-floor kohi
        // sits FLAT at profiles/kohi.yml, not under profiles/legacy/.
        Path flat = dataFolder.resolve(ConfigStore.PROFILES_DIR).resolve("kohi.yml");
        Files.createDirectories(flat.getParent());
        Files.writeString(flat, archived("kohi@1.8.0.yml"), StandardCharsets.UTF_8);

        store.ensureDefaultFiles();

        // Upgraded in place at the flat path — never duplicated into the folder.
        assertEquals(currentBundle("kohi"), Files.readString(flat, StandardCharsets.UTF_8),
                "a flat pre-folder kohi must upgrade in place");
        assertFalse(Files.exists(profile("kohi")),
                "extraction must not create a foldered duplicate when a flat file already exists");
    }

    @Test
    void sameStemTwinsResolveDeterministicallyWithAWarning() throws Exception {
        ConfigStore store = store();
        store.ensureDefaultFiles();
        // A user leaves a flat kohi.yml next to the extracted legacy/kohi.yml.
        // Files.walk order is filesystem-dependent, so without the shallowest-
        // first sort the winner could flip between boots; the flat file must
        // win every time and the shadowed twin must be named in the log.
        Path flat = dataFolder.resolve(ConfigStore.PROFILES_DIR).resolve("kohi.yml");
        Files.writeString(flat, "display-name: Flat Wins\n", StandardCharsets.UTF_8);

        var sources = store.loadSources();

        assertEquals("Flat Wins", sources.profiles().get("kohi").getString("display-name"),
                "the shallowest same-stem file must win deterministically");
        assertTrue(logged.stream().anyMatch(line ->
                        line.contains("legacy/kohi.yml") && line.contains("already loaded")),
                () -> "expected a duplicate-profile warning, logged: " + logged);
    }

    @Test
    void unTunedSupersededPresetIsUpgradedInPlace() throws Exception {
        ConfigStore store = store();
        Files.createDirectories(profile("kohi").getParent());
        // The RAW, unpatched 1.3.0 kohi: ensureDeliverySection inserts the delivery
        // block first, THEN the byte hash matches the patched 1.3.x form and the upgrade
        // overwrites with the current bundle. The end-to-end patch → hash → upgrade proof.
        Files.writeString(profile("kohi"), archived("kohi@1.3.x-raw.yml"), StandardCharsets.UTF_8);

        store.ensureDefaultFiles();

        assertEquals(currentBundle("kohi"), Files.readString(profile("kohi"), StandardCharsets.UTF_8),
                "an unedited pre-1.4.0 kohi must upgrade to the current bundle byte-for-byte");
        assertTrue(loggedUpgrade("kohi.yml"), () -> "expected an upgrade report, logged: " + logged);

        String afterFirst = Files.readString(profile("kohi"), StandardCharsets.UTF_8);
        store.ensureDefaultFiles();
        assertEquals(afterFirst, Files.readString(profile("kohi"), StandardCharsets.UTF_8),
                "the upgraded file IS the current bundle — a second pass never re-flags it");
    }

    @Test
    void unTunedSignaturePresetUpgradesToTheVerticalTuning() throws Exception {
        ConfigStore store = store();
        Files.createDirectories(profile("signature").getParent());
        Files.writeString(profile("signature"), archived("signature@2.2.0.yml"), StandardCharsets.UTF_8);

        store.ensureDefaultFiles();

        YamlConfiguration upgraded = YamlConfiguration.loadConfiguration(profile("signature").toFile());
        assertEquals(0.365, upgraded.getDouble("knockback.base.vertical"),
                "the upgrade must carry the 2.2.1 base-vertical lift");
        assertEquals(0.98, upgraded.getDouble("knockback.air.vertical"),
                "the upgrade must carry the 2.2.1 airborne vertical trim");
        assertEquals(0.92, upgraded.getDouble("knockback.air.horizontal"),
                "the horizontal pocket trim is unchanged");
        assertTrue(loggedUpgrade("signature.yml"), () -> "expected an upgrade report, logged: " + logged);

        String afterFirst = Files.readString(profile("signature"), StandardCharsets.UTF_8);
        store.ensureDefaultFiles();
        assertEquals(afterFirst, Files.readString(profile("signature"), StandardCharsets.UTF_8));
    }

    @Test
    void tunedSignaturePresetIsNeverTouched() throws Exception {
        ConfigStore store = store();
        Files.createDirectories(profile("signature").getParent());
        String tuned = archived("signature@2.2.0.yml").replace("horizontal: 0.92", "horizontal: 0.95");
        Files.writeString(profile("signature"), tuned, StandardCharsets.UTF_8);

        store.ensureDefaultFiles();

        YamlConfiguration kept = YamlConfiguration.loadConfiguration(profile("signature").toFile());
        assertEquals(0.95, kept.getDouble("knockback.air.horizontal"),
                "owner-tuned values must survive every upgrade pass");
        assertEquals(1.0, kept.getDouble("knockback.air.vertical"), "an owner-tuned file is never upgraded");
        assertFalse(loggedUpgrade("signature.yml"),
                () -> "no upgrade may be reported for a tuned file, logged: " + logged);
    }

    @Test
    void tunedSupersededPresetIsNeverTouched() throws Exception {
        ConfigStore store = store();
        Files.createDirectories(profile("mmc").getParent());
        String tuned = archived("mmc@1.8.0.yml").replace("horizontal: 0.32", "horizontal: 0.42");
        Files.writeString(profile("mmc"), tuned, StandardCharsets.UTF_8);

        store.ensureDefaultFiles();

        YamlConfiguration kept = YamlConfiguration.loadConfiguration(profile("mmc").toFile());
        assertEquals(0.42, kept.getDouble("knockback.base.horizontal"),
                "owner-tuned values must survive every upgrade pass");
        assertEquals("add", kept.getString("knockback.vertical-mode"));
        assertFalse(loggedUpgrade("mmc.yml"),
                () -> "no upgrade may be reported for a tuned file, logged: " + logged);
    }

    @Test
    void unTunedPreFloorKohiUpgradesToThePracticeFloor() throws Exception {
        ConfigStore store = store();
        Files.createDirectories(profile("kohi").getParent());
        // kohi exactly as 1.8.0 → 2.4.6 shipped it (vertical-min −3.9, archived values).
        Files.writeString(profile("kohi"), archived("kohi@1.8.0.yml"), StandardCharsets.UTF_8);

        store.ensureDefaultFiles();

        YamlConfiguration upgraded = YamlConfiguration.loadConfiguration(profile("kohi").toFile());
        assertEquals(0.0, upgraded.getDouble("knockback.limits.vertical-min"),
                "an unedited pre-floor kohi must gain the practice floor");
        assertEquals(0.35, upgraded.getDouble("knockback.base.horizontal"),
                "the archived kohi values are untouched by the floor upgrade");
        assertTrue(loggedUpgrade("kohi.yml"), () -> "expected an upgrade report, logged: " + logged);

        String afterFirst = Files.readString(profile("kohi"), StandardCharsets.UTF_8);
        store.ensureDefaultFiles();
        assertEquals(afterFirst, Files.readString(profile("kohi"), StandardCharsets.UTF_8));
    }

    @Test
    void tunedPreFloorKohiKeepsItsOldFloorForever() throws Exception {
        ConfigStore store = store();
        Files.createDirectories(profile("kohi").getParent());
        String tuned = archived("kohi@1.8.0.yml").replace("horizontal: 0.35", "horizontal: 0.37");
        Files.writeString(profile("kohi"), tuned, StandardCharsets.UTF_8);

        store.ensureDefaultFiles();

        YamlConfiguration kept = YamlConfiguration.loadConfiguration(profile("kohi").toFile());
        assertEquals(0.37, kept.getDouble("knockback.base.horizontal"),
                "owner-tuned values must survive every upgrade pass");
        assertEquals(-3.9, kept.getDouble("knockback.limits.vertical-min"),
                "a tuned file keeps its own floor — frozen forever");
        assertFalse(loggedUpgrade("kohi.yml"),
                () -> "no upgrade may be reported for a tuned file, logged: " + logged);
    }

    @Test
    void unTunedPreFloorLegacyUpgradesToTheOwnerFloor() throws Exception {
        ConfigStore store = store();
        Files.createDirectories(profile("legacy-1.7").getParent());
        // legacy-1.7 exactly as 2.4.7 shipped it (vertical-min −3.9, era values).
        Files.writeString(profile("legacy-1.7"), archived("legacy-1.7@2.4.7.yml"), StandardCharsets.UTF_8);

        store.ensureDefaultFiles();

        YamlConfiguration upgraded = YamlConfiguration.loadConfiguration(profile("legacy-1.7").toFile());
        assertEquals(0.0, upgraded.getDouble("knockback.limits.vertical-min"),
                "an unedited pre-floor legacy-1.7 must gain the 2.4.8 owner floor");
        assertEquals(0.4, upgraded.getDouble("knockback.base.horizontal"),
                "the era values are untouched by the floor upgrade");
        assertTrue(loggedUpgrade("legacy-1.7.yml"), () -> "expected an upgrade report, logged: " + logged);

        String afterFirst = Files.readString(profile("legacy-1.7"), StandardCharsets.UTF_8);
        store.ensureDefaultFiles();
        assertEquals(afterFirst, Files.readString(profile("legacy-1.7"), StandardCharsets.UTF_8));
    }

    @Test
    void tunedPreFloorLegacyKeepsItsOldFloorForever() throws Exception {
        ConfigStore store = store();
        Files.createDirectories(profile("legacy-1.7").getParent());
        String tuned = archived("legacy-1.7@2.4.7.yml").replace("vertical-min: -3.9", "vertical-min: -2.0");
        Files.writeString(profile("legacy-1.7"), tuned, StandardCharsets.UTF_8);

        store.ensureDefaultFiles();

        YamlConfiguration kept = YamlConfiguration.loadConfiguration(profile("legacy-1.7").toFile());
        assertEquals(-2.0, kept.getDouble("knockback.limits.vertical-min"),
                "a tuned file keeps its own floor — frozen forever");
        assertFalse(loggedUpgrade("legacy-1.7.yml"),
                () -> "no upgrade may be reported for a tuned file, logged: " + logged);
    }

    @Test
    void ownerUnfloorEditOnTheCurrentBundleIsFrozen() throws Exception {
        ConfigStore store = store();
        Files.createDirectories(profile("legacy-1.7").getParent());
        // The current bundle's OWN comment invites this edit ("Restore -3.9 to unfloor").
        // It parses to exactly the LEGACY17_2_4_7 values the old value-match reverted, but
        // its BYTES differ from every archived revision (the current comment block differs
        // from every one ever shipped), so byte identity freezes it — owner edits are sacred.
        String edited = currentBundle("legacy-1.7").replace("vertical-min: 0.0", "vertical-min: -3.9");
        Files.writeString(profile("legacy-1.7"), edited, StandardCharsets.UTF_8);

        store.ensureDefaultFiles();
        store.ensureDefaultFiles();

        assertEquals(edited, Files.readString(profile("legacy-1.7"), StandardCharsets.UTF_8),
                "an owner's -3.9 unfloor edit on the current bundle must survive byte-identical");
        assertTrue(Files.readString(profile("legacy-1.7"), StandardCharsets.UTF_8).contains("vertical-min: -3.9"),
                "the edit must take effect (not be reverted to the 0.0 floor)");
        assertFalse(loggedUpgrade("legacy-1.7.yml"),
                () -> "an owner edit must never be reported as upgraded, logged: " + logged);
    }

    @Test
    void ownerSignatureBlockDeletionIsFrozen() throws Exception {
        ConfigStore store = store();
        Files.createDirectories(profile("signature").getParent());
        // Deleting the whole speed-scaling block makes the file parse to the
        // SIGNATURE_2_2_1 values (pace OFF) — the case the old value-match would revert —
        // but its bytes match no archived signature form, so it must freeze.
        String current = currentBundle("signature");
        String edited = current.substring(0, current.indexOf("  speed-scaling:"));
        Files.writeString(profile("signature"), edited, StandardCharsets.UTF_8);

        store.ensureDefaultFiles();
        store.ensureDefaultFiles();

        assertEquals(edited, Files.readString(profile("signature"), StandardCharsets.UTF_8),
                "an owner who removed the speed-scaling block must keep their file byte-identical");
        assertFalse(loggedUpgrade("signature.yml"),
                () -> "an owner edit must never be reported as upgraded, logged: " + logged);
    }

    @Test
    void splitFileOwnerEditsSurviveAndADeletedSplitFileRegenerates() throws Exception {
        // The 2.5.2 split files carry the profiles contract: extracted only when
        // missing — owner edits are sacred, deleting regenerates pristine.
        ConfigStore store = store();
        store.ensureDefaultFiles();
        Path pots = dataFolder.resolve(ConfigStore.POTS_FILE);

        Files.writeString(pots, "pot-fill:\n  cost-per-potion: 5.0\n", StandardCharsets.UTF_8);
        Files.delete(dataFolder.resolve(ConfigStore.LOADOUT_FILE));

        store.ensureDefaultFiles();

        assertEquals("pot-fill:\n  cost-per-potion: 5.0\n",
                Files.readString(pots, StandardCharsets.UTF_8),
                "an owner-edited split file must never be overwritten");
        assertTrue(Files.isRegularFile(dataFolder.resolve(ConfigStore.LOADOUT_FILE)),
                "a deleted split file must regenerate pristine");
        assertEquals(resource(ConfigStore.LOADOUT_FILE),
                Files.readString(dataFolder.resolve(ConfigStore.LOADOUT_FILE), StandardCharsets.UTF_8),
                "the regenerated split file is the bundle byte-for-byte");
    }

    @Test
    void splitFileExtractionIsSuppressedWhileConfigYmlCarriesTheOldSection() throws Exception {
        // An upgraded install whose config.yml still carries a moved section: the
        // pristine split bundle must NOT extract (it would shadow the tuned
        // old-location section — the parser honours config.yml and says so).
        // Deleting the section releases the extraction on the next pass.
        Files.writeString(dataFolder.resolve(ConfigStore.MAIN_FILE), """
                config-version: 4
                modules:
                  combo-hold: true
                combo-hold:
                  gain: 0.9
                """, StandardCharsets.UTF_8);
        ConfigStore store = store();

        store.ensureDefaultFiles();

        assertFalse(Files.exists(dataFolder.resolve(ConfigStore.COMBO_FILE)),
                "combo.yml must not extract while config.yml carries combo-hold");
        // Sections that did NOT stay behind extract normally.
        assertTrue(Files.isRegularFile(dataFolder.resolve(ConfigStore.POTS_FILE)));
        assertTrue(Files.isRegularFile(dataFolder.resolve(ConfigStore.LOADOUT_FILE)));

        // The owner moves on: the section leaves config.yml, the bundle extracts.
        Files.writeString(dataFolder.resolve(ConfigStore.MAIN_FILE), """
                config-version: 4
                modules:
                  combo-hold: true
                """, StandardCharsets.UTF_8);
        store.ensureDefaultFiles();
        assertTrue(Files.isRegularFile(dataFolder.resolve(ConfigStore.COMBO_FILE)),
                "deleting the old-location section releases the split extraction");
    }

    /* ------------------------------------------------------------------ */
    /*  The Combat Effects preset library (2.5.3)                          */
    /* ------------------------------------------------------------------ */

    @Test
    void effectsPresetOwnerEditsSurviveAndDeletedPresetsRegenerate() throws Exception {
        // The preset library rides the exact profiles contract: extracted only
        // when missing, owner edits sacred, deleting regenerates pristine.
        ConfigStore store = store();
        store.ensureDefaultFiles();

        Files.writeString(effectsPreset("custom"), "display-name: Mine\n", StandardCharsets.UTF_8);
        Files.delete(effectsPreset("signature"));

        store.ensureDefaultFiles();

        assertEquals("display-name: Mine\n",
                Files.readString(effectsPreset("custom"), StandardCharsets.UTF_8),
                "an owner-edited effects preset must never be overwritten");
        assertTrue(Files.isRegularFile(effectsPreset("signature")),
                "a deleted effects preset must regenerate");
        assertEquals(resource(ConfigStore.EFFECTS_PRESETS_DIR + "/signature.yml"),
                Files.readString(effectsPreset("signature"), StandardCharsets.UTF_8),
                "the regenerated preset is the bundle byte-for-byte");
    }

    @Test
    void effectsExtractionIsSuppressedWhileTheLegacyEffectsLayoutAwaitsMigration() throws Exception {
        // A 2.5.2 tree (version < 4 with the old per-module files): effects.yml
        // and custom.yml must NOT extract — the 3→4 migration owns creating both
        // (custom.yml's first extraction IS the import of the old effective
        // values, and effects.yml must come up with custom selected). signature
        // is a new-library file the migration never touches, so it extracts
        // normally.
        Files.writeString(dataFolder.resolve(ConfigStore.MAIN_FILE),
                "config-version: 3\n", StandardCharsets.UTF_8);
        Path oldHitFeedback = dataFolder.resolve("effects/hit-feedback.yml");
        Files.createDirectories(oldHitFeedback.getParent());
        Files.writeString(oldHitFeedback, "hit-feedback:\n  preset: signature\n", StandardCharsets.UTF_8);
        ConfigStore store = store();

        store.ensureDefaultFiles();

        assertFalse(Files.exists(dataFolder.resolve(ConfigStore.EFFECTS_FILE)),
                "effects.yml must not extract while the 2.5.2 layout awaits migration");
        assertFalse(Files.exists(effectsPreset("custom")),
                "custom.yml must not extract — the migration's import is its first extraction");
        assertTrue(Files.isRegularFile(effectsPreset("signature")));

        // Post-migration (stamped 4, old file moved away): both extract.
        Files.writeString(dataFolder.resolve(ConfigStore.MAIN_FILE),
                "config-version: 4\n", StandardCharsets.UTF_8);
        Files.delete(oldHitFeedback);
        store.ensureDefaultFiles();
        assertTrue(Files.isRegularFile(dataFolder.resolve(ConfigStore.EFFECTS_FILE)),
                "the migration stamp releases the effects.yml extraction");
        assertTrue(Files.isRegularFile(effectsPreset("custom")));
    }

    @Test
    void loadSourcesServesTheEffectsSelectionAndEveryPresetByStem() throws Exception {
        ConfigStore store = store();
        store.ensureDefaultFiles();
        // A hand-added preset joins the library by dropping a file in.
        Files.writeString(effectsPreset("mine"), "display-name: Mine\n", StandardCharsets.UTF_8);

        ConfigStore.Sources sources = store.loadSources();

        assertEquals("signature", sources.effects().getString("effects.preset"),
                "the shipped selection is signature");
        assertEquals(ConfigStore.BUNDLED_EFFECTS_PRESETS.size() + 1, sources.effectsPresets().size());
        assertNotNull(sources.effectsPresets().get("mine"));
        assertNotNull(sources.effectsPresets().get("signature"));
    }

    @Test
    void loadSourcesServesTheSignatureResourceWhenTheDiskFileIsMissing() throws Exception {
        // The signature tune is the default preset — it must survive a torn
        // install: with its disk file gone, loadSources serves the JAR resource
        // (a loud line), so the signature stem is always resolvable.
        ConfigStore store = store();
        store.ensureDefaultFiles();
        Files.delete(effectsPreset("signature"));

        ConfigStore.Sources sources = store.loadSources();

        var signature = sources.effectsPresets().get("signature");
        assertNotNull(signature, "signature must be served from the jar when its disk file is gone");
        assertFalse(signature.getKeys(false).isEmpty(),
                "the served signature carries the bundled tune, not an empty config");
        assertTrue(logged.stream().anyMatch(line ->
                        line.contains("signature.yml") && line.contains("jar")),
                () -> "expected a loud resource-fallback line, logged: " + logged);
    }

    /* ------------------------------------------------------------------ */
    /*  The rules-bundle library (2.8.x)                                   */
    /* ------------------------------------------------------------------ */

    private Path bundle(String name) {
        return dataFolder.resolve(ConfigStore.BUNDLES_DIR).resolve(name + ".yml");
    }

    @Test
    void everyBundleExtractsOnceOwnerEditsSurviveAndDeletedBundlesRegenerate() throws Exception {
        // The rules-bundle library rides the exact profiles/effects contract:
        // extracted only when missing, owner edits sacred, deleting regenerates.
        ConfigStore store = store();
        store.ensureDefaultFiles();

        for (String name : ConfigStore.BUNDLED_BUNDLES) {
            assertTrue(Files.isRegularFile(bundle(name)), name + " bundle not extracted");
        }

        Files.writeString(bundle("ct8c"), "display-name: Mine\n", StandardCharsets.UTF_8);
        Files.delete(bundle("vanilla"));

        store.ensureDefaultFiles();

        assertEquals("display-name: Mine\n", Files.readString(bundle("ct8c"), StandardCharsets.UTF_8),
                "an owner-edited bundle must never be overwritten");
        assertTrue(Files.isRegularFile(bundle("vanilla")), "a deleted bundle must regenerate");
        assertEquals(resource(ConfigStore.BUNDLES_DIR + "/vanilla.yml"),
                Files.readString(bundle("vanilla"), StandardCharsets.UTF_8),
                "the regenerated bundle is the resource byte-for-byte");
    }

    @Test
    void loadBundlesServesEveryBundleByStem() throws Exception {
        ConfigStore store = store();
        store.ensureDefaultFiles();
        // A hand-added bundle joins the library by dropping a file in.
        Files.writeString(bundle("mine"), "display-name: Mine\nmodules:\n  knockback: false\n",
                StandardCharsets.UTF_8);

        var bundles = store.loadBundles();

        assertEquals(ConfigStore.BUNDLED_BUNDLES.size() + 1, bundles.size());
        assertNotNull(bundles.get("ct8c"));
        assertNotNull(bundles.get("signature"));
        assertNotNull(bundles.get("vanilla"));
        assertNotNull(bundles.get("mine"));
        assertEquals("ct8c", bundles.get("ct8c").getString("knockback-profile"));
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
