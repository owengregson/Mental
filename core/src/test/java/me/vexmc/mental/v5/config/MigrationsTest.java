package me.vexmc.mental.v5.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import me.vexmc.mental.v5.config.settings.DeathEffectsSettings;
import me.vexmc.mental.v5.config.settings.HitFeedbackSettings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The explicit config-version chain: 1 → 2 splits the single-file layout and
 * carries tuned knockback into profiles/custom.yml, 2 → 3 creates the overlay
 * and stamps the version, 3 → 4 retires the 2.5.2 per-module effects files
 * into the Combat Effects preset library (the old EFFECTIVE values — preset
 * enums resolved — are imported into effects/presets/custom.yml, custom is
 * selected in effects.yml, and the old files are backed up). Each step backs
 * up first and is idempotent; the version is READ.
 */
class MigrationsTest {

    @TempDir
    Path dataFolder;

    private final List<String> logged = new ArrayList<>();

    private final Function<String, InputStream> resources = name ->
            getClass().getClassLoader().getResourceAsStream(name);

    /* The 2.5.2 signature effective values the 3 → 4 import must reproduce (the
     * deleted SIGNATURE_* constants, pinned test-locally now). */
    private static final List<HitFeedbackSettings.SoundSpec> SIGNATURE_HIT_SOUNDS = List.of(
            new HitFeedbackSettings.SoundSpec("block.lodestone.break", 1.0f, 1.0f),
            new HitFeedbackSettings.SoundSpec("entity.generic.hurt", 0.85f, 0.75f),
            new HitFeedbackSettings.SoundSpec("entity.breeze.deflect", 0.75f, 1.15f));
    private static final List<HitFeedbackSettings.SoundSpec> SIGNATURE_LOW_HEALTH_SOUNDS =
            List.of(new HitFeedbackSettings.SoundSpec("entity.glow_squid.hurt", 0.9f, 1.2f));
    private static final List<Integer> SIGNATURE_FIREWORK_COLORS = List.of(0xFFFFFF, 0xFFFF55, 0xFFAA00);

    private Migrations migrations() {
        return new Migrations(dataFolder, resources, logged::add);
    }

    private String pristineVanilla() throws Exception {
        try (InputStream in = resources.apply("v5/migration/pristine-vanilla.yml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void installFixture(String tree) throws Exception {
        try (InputStream in = resources.apply("v5/migration/" + tree + "/config.yml")) {
            Files.writeString(dataFolder.resolve("config.yml"),
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        }
    }

    private YamlConfiguration load(String file) {
        return YamlConfiguration.loadConfiguration(dataFolder.resolve(file).toFile());
    }

    @Test
    void v1TunedMigratesThroughTheChainToTheSplitV3Layout() throws Exception {
        installFixture("v1");

        Migrations.Result result = migrations().migrate();

        assertEquals(1, result.fromVersion());
        assertEquals(5, result.toVersion());
        assertEquals(List.of(2, 3, 4, 5), result.stepsApplied());

        // Both step backups exist; the version is now stamped 5.
        assertTrue(Files.isRegularFile(dataFolder.resolve("config-backup-v1/config.yml")));
        assertTrue(Files.isRegularFile(dataFolder.resolve("config-backup-v2/config.yml")));
        assertTrue(Files.isRegularFile(dataFolder.resolve("state/overrides.yml")));

        YamlConfiguration main = load("config.yml");
        assertFalse(Migrations.isLegacyLayout(main), "migrated config.yml still legacy-shaped");
        assertEquals(5, main.getInt("config-version"));
        assertFalse(main.getBoolean("modules.latency-compensation", true), "disabled toggle carried");
        assertTrue(main.getBoolean("modules.hit-registration", false), "enabled toggle kept from template");
        assertEquals("force-safe", main.getString("anticheat.mode"));
        assertEquals(List.of("GrimAC"), main.getStringList("anticheat.known"));
        assertTrue(main.getBoolean("debug.enabled"));

        assertEquals(16, load("hit-registration.yml").getInt("hit-registration.max-cps"));
        assertFalse(load("hit-registration.yml").getBoolean("hit-registration.fast-path.simulate-crits", true));
        assertEquals(40, load("latency-compensation.yml").getInt("latency-compensation.ping-offset-ms"));

        YamlConfiguration knockback = load("knockback.yml");
        assertEquals("custom", knockback.getString("knockback.profile"));
        assertEquals("cancel", knockback.getString("fishing-knockback.reel-in"));

        YamlConfiguration custom = load("profiles/legacy/custom.yml");
        assertEquals(0.42, custom.getDouble("knockback.base.horizontal"));
        assertEquals(0.36, custom.getDouble("knockback.base.vertical"));
        assertEquals(1.5, custom.getDouble("knockback.modifiers.sprint"));
        assertFalse(custom.getBoolean("knockback.modifiers.combos", true));

        // End to end: the migrated tree parses into the tuned profile.
        ConfigStore.Sources sources = new ConfigStore(dataFolder, resources, message -> {}).loadSources();
        SnapshotParser.Result parsed = SnapshotParser.parse(sources);
        assertFalse(parsed.snapshot().enabled(
                me.vexmc.mental.v5.feature.Feature.LATENCY_COMPENSATION));
        assertEquals("custom", parsed.snapshot().profileFor("world").name());
        assertEquals(0.42, parsed.snapshot().profileFor("world").base().horizontal());
    }

    @Test
    void v1UntunedKeepsTheLegacyProfileSelected() throws Exception {
        Files.writeString(dataFolder.resolve("config.yml"), """
                config-version: 1
                modules:
                  knockback:
                    enabled: true
                    base:
                      horizontal: 0.4
                      vertical: 0.4
                """, StandardCharsets.UTF_8);

        migrations().migrate();
        // custom.yml is NOT migrated (untouched knockback) — the bundle regenerates it.
        new ConfigStore(dataFolder, resources, message -> {}).ensureDefaultFiles();

        assertEquals("legacy-1.7", load("knockback.yml").getString("knockback.profile"));
        assertEquals("Custom", load("profiles/legacy/custom.yml").getString("display-name"),
                "an untuned migration keeps the bundled custom preset, not a migrated one");
    }

    @Test
    void v2StampsThroughTheChainAndCreatesTheOverlayIdempotently() throws Exception {
        installFixture("v2");

        Migrations.Result first = migrations().migrate();
        assertEquals(2, first.fromVersion());
        assertEquals(5, first.toVersion());
        assertEquals(List.of(3, 4, 5), first.stepsApplied());
        assertTrue(Files.isRegularFile(dataFolder.resolve("config-backup-v2/config.yml")));
        assertTrue(Files.isRegularFile(dataFolder.resolve("state/overrides.yml")));
        assertEquals(5, load("config.yml").getInt("config-version"));

        // Idempotent: a second run reads version 5 and does nothing.
        byte[] afterFirst = Files.readAllBytes(dataFolder.resolve("config.yml"));
        Migrations.Result second = migrations().migrate();
        assertEquals(5, second.fromVersion());
        assertTrue(second.stepsApplied().isEmpty());
        assertEquals(-1, java.util.Arrays.mismatch(afterFirst,
                Files.readAllBytes(dataFolder.resolve("config.yml"))), "config.yml unchanged on re-run");
    }

    @Test
    void freshTreeAndAlreadyMigratedTreeAreNoOps() throws Exception {
        // No config.yml on disk → nothing to migrate.
        Migrations.Result fresh = migrations().migrate();
        assertEquals(5, fresh.fromVersion());
        assertTrue(fresh.stepsApplied().isEmpty());
        assertFalse(Files.exists(dataFolder.resolve("config-backup-v1")));
        assertFalse(Files.exists(dataFolder.resolve("config-backup-v2")));

        // An already-v5 tree is untouched.
        Files.writeString(dataFolder.resolve("config.yml"), """
                config-version: 5
                modules:
                  hit-feedback: false
                """, StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(dataFolder.resolve("config.yml"));
        Migrations.Result already = migrations().migrate();
        assertEquals(5, already.fromVersion());
        assertTrue(already.stepsApplied().isEmpty());
        assertEquals(-1, java.util.Arrays.mismatch(before,
                Files.readAllBytes(dataFolder.resolve("config.yml"))));
    }

    @Test
    void theWholeChainIsIdempotentOnAnAlreadyMigratedTree() throws Exception {
        installFixture("v1");
        migrations().migrate();
        byte[] afterFirst = Files.readAllBytes(dataFolder.resolve("config.yml"));

        Migrations.Result second = migrations().migrate();
        assertEquals(5, second.fromVersion());
        assertTrue(second.stepsApplied().isEmpty());
        assertEquals(-1, java.util.Arrays.mismatch(afterFirst,
                Files.readAllBytes(dataFolder.resolve("config.yml"))));
    }

    /* ------------------------------------------------------------------ */
    /*  3 → 4 : the Combat Effects preset-library migration                */
    /* ------------------------------------------------------------------ */

    private void installV3Config() throws Exception {
        Files.writeString(dataFolder.resolve("config.yml"), """
                config-version: 3
                modules:
                  hit-feedback: true
                """, StandardCharsets.UTF_8);
    }

    private void writeOldEffectsFile(String name, String body) throws Exception {
        Path file = dataFolder.resolve("effects").resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body, StandardCharsets.UTF_8);
    }

    private EffectsPreset importedCustom() {
        Path custom = dataFolder.resolve(ConfigStore.EFFECTS_PRESETS_DIR).resolve("custom.yml");
        assertTrue(Files.isRegularFile(custom), "effects/presets/custom.yml must be imported");
        return EffectsPresetParser.parse("custom",
                YamlConfiguration.loadConfiguration(custom.toFile()), new ConfigIssues());
    }

    @Test
    void a252SignatureSelectionImportsIntoCustomAndSelectsIt() throws Exception {
        // A 2.5.2 owner running the signature tune: the old per-module preset
        // enums resolve to the signature VALUES, land in custom.yml, and custom
        // is selected — the server comes up identical-sounding.
        installV3Config();
        writeOldEffectsFile("hit-feedback.yml", """
                hit-feedback:
                  preset: signature
                  low-health-threshold-hearts: 4.0
                """);
        writeOldEffectsFile("damage-indicators.yml", """
                damage-indicators:
                  ring-radius: 0.9
                """);
        writeOldEffectsFile("death-effects.yml", """
                death-effects:
                  preset: signature
                """);

        Migrations.Result result = migrations().migrate();

        assertEquals(3, result.fromVersion());
        assertEquals(5, result.toVersion());
        assertEquals(List.of(4, 5), result.stepsApplied());
        assertEquals(5, load("config.yml").getInt("config-version"));

        // The old files moved into the v3 backup — the live effects/ dir keeps
        // only the new preset library.
        for (String name : List.of("hit-feedback.yml", "damage-indicators.yml", "death-effects.yml")) {
            assertFalse(Files.exists(dataFolder.resolve("effects").resolve(name)),
                    name + " must be moved out of effects/");
            assertTrue(Files.isRegularFile(dataFolder.resolve("config-backup-v3/effects").resolve(name)),
                    name + " must be backed up");
        }

        // The import carries the RESOLVED effective values.
        EffectsPreset custom = importedCustom();
        assertEquals(SIGNATURE_HIT_SOUNDS, custom.hitFeedback().sounds());
        assertEquals(SIGNATURE_LOW_HEALTH_SOUNDS, custom.hitFeedback().lowHealthSounds());
        assertEquals(40.0, custom.hitFeedback().lowHealthThresholdPercent(), 0.0,
                "the 2.5.2 4-heart threshold imports as 40% of max health");
        assertEquals(0.9, custom.damageIndicators().ringRadius(), 1e-9,
                "the tuned indicator knob imports verbatim");
        assertTrue(custom.deathEffects().lightning(), "the signature death strike imports");
        assertEquals(SIGNATURE_FIREWORK_COLORS, custom.deathEffects().fireworkColors());

        // custom is selected in the freshly created effects.yml, comments intact.
        YamlConfiguration effects = load(ConfigStore.EFFECTS_FILE);
        assertEquals("custom", effects.getString("effects.preset"));
        assertTrue(Files.readString(dataFolder.resolve(ConfigStore.EFFECTS_FILE), StandardCharsets.UTF_8)
                        .contains("#"), "the selection file keeps its documentation");

        // One loud line per action.
        assertTrue(logged.stream().anyMatch(line -> line.contains("custom.yml")),
                () -> "expected an import line, logged: " + logged);

        // Idempotent: a second run applies nothing.
        Migrations.Result second = migrations().migrate();
        assertTrue(second.stepsApplied().isEmpty());
    }

    @Test
    void a252CustomizedListImportsVerbatim() throws Exception {
        // preset: custom with hand-written lists — those exact lists import.
        installV3Config();
        writeOldEffectsFile("hit-feedback.yml", """
                hit-feedback:
                  preset: custom
                  sounds:
                    - sound: block.anvil.land
                      volume: 0.5
                      pitch: 0.6
                  low-health-threshold-hearts: 6.0
                """);

        migrations().migrate();

        EffectsPreset custom = importedCustom();
        assertEquals(1, custom.hitFeedback().sounds().size());
        assertEquals("block.anvil.land", custom.hitFeedback().sounds().get(0).sound());
        assertEquals(0.5f, custom.hitFeedback().sounds().get(0).volume(), 1e-6);
        assertEquals(60.0, custom.hitFeedback().lowHealthThresholdPercent(), 0.0,
                "the 2.5.2 6-heart threshold imports as 60% of max health");
        // Absent old files resolve to their vanilla no-op: a strict death nothing.
        assertEquals(DeathEffectsSettings.DEFAULTS, custom.deathEffects());
        assertEquals("custom", load(ConfigStore.EFFECTS_FILE).getString("effects.preset"));
    }

    @Test
    void a252VanillaTreeStillImportsItsEffectiveVanillaValues() throws Exception {
        // Even an untouched 2.5.2 tree migrates predictably: the resolved vanilla
        // values land in custom.yml and custom is selected — identical-sounding
        // by construction (custom carries exactly what vanilla did).
        installV3Config();
        writeOldEffectsFile("hit-feedback.yml", "hit-feedback:\n  preset: vanilla\n");
        writeOldEffectsFile("death-effects.yml", "death-effects:\n  preset: vanilla\n");

        migrations().migrate();

        EffectsPreset custom = importedCustom();
        // The 2.5.2 vanilla tune imports faithfully: the era sounds, no particles,
        // no layer, and its 4-heart threshold as 40% (the 2.5.2 default, converted
        // at write time — never silently reset to the new 35% default).
        assertEquals(new HitFeedbackSettings(
                        HitFeedbackSettings.VANILLA_SOUNDS, List.of(), List.of(), 40.0),
                custom.hitFeedback());
        assertEquals(DeathEffectsSettings.DEFAULTS, custom.deathEffects());
        assertEquals("custom", load(ConfigStore.EFFECTS_FILE).getString("effects.preset"));
    }

    @Test
    void configYmlOnlyEffectsSectionsImportToo() throws Exception {
        // A pre-2.5.2 in-place upgrade whose config.yml still carried the honoured
        // old-location sections (the split files never extracted): those sections
        // are the old effective config and import the same way.
        Files.writeString(dataFolder.resolve("config.yml"), """
                config-version: 3
                modules:
                  hit-feedback: true
                hit-feedback:
                  preset: signature
                """, StandardCharsets.UTF_8);

        Migrations.Result result = migrations().migrate();

        assertEquals(List.of(4, 5), result.stepsApplied());
        EffectsPreset custom = importedCustom();
        assertEquals(SIGNATURE_HIT_SOUNDS, custom.hitFeedback().sounds());
        assertEquals("custom", load(ConfigStore.EFFECTS_FILE).getString("effects.preset"));
        assertTrue(logged.stream().anyMatch(line ->
                        line.contains("config.yml") && line.contains("hit-feedback")),
                () -> "expected a loud config.yml-section line, logged: " + logged);
    }

    @Test
    void importIsSuppressedWhenCustomAlreadyExistsAndTheChainSettlesOnSignature() throws Exception {
        // The import IS custom.yml's extraction — an already-present custom.yml is
        // an owner file and must never be overwritten; without an import the 3→4
        // step leaves the default signature selection (selecting unknown custom
        // values could change the sound silently) and reports the skip loudly.
        // The 4→5 step finds nothing to flip, so the chain settles on signature
        // (the owner re-selects custom if they want it — the file is preserved).
        installV3Config();
        writeOldEffectsFile("hit-feedback.yml", "hit-feedback:\n  preset: signature\n");
        Path custom = dataFolder.resolve(ConfigStore.EFFECTS_PRESETS_DIR).resolve("custom.yml");
        Files.createDirectories(custom.getParent());
        Files.writeString(custom, "display-name: Mine\n", StandardCharsets.UTF_8);

        migrations().migrate();

        assertEquals("display-name: Mine\n", Files.readString(custom, StandardCharsets.UTF_8),
                "an existing custom.yml is sacred — never overwritten by the import");
        assertEquals("signature", load(ConfigStore.EFFECTS_FILE).getString("effects.preset"),
                "3→4 keeps vanilla, then 4→5 flips the retired vanilla to signature");
        assertTrue(Files.isRegularFile(
                        dataFolder.resolve("config-backup-v3/effects/hit-feedback.yml")),
                "the old file is still backed up out of the way");
        assertTrue(logged.stream().anyMatch(line -> line.contains("already exists")),
                () -> "expected a loud skip line, logged: " + logged);
    }

    @Test
    void theFullBootOrderKeepsA252SignatureServerIdenticalSounding() throws Exception {
        // The exact boot sequence (extract → migrate → re-extract → parse) on a
        // 2.5.2 tree running the signature tune: the first extraction pass must
        // NOT preempt the import (effects.yml/custom.yml suppressed while the
        // migration is pending), and the parsed snapshot must come up selecting
        // custom with the signature-valued tune — no silent behavior change.
        installV3Config();
        writeOldEffectsFile("hit-feedback.yml", "hit-feedback:\n  preset: signature\n");
        writeOldEffectsFile("death-effects.yml", "death-effects:\n  preset: signature\n");
        ConfigStore store = new ConfigStore(dataFolder, resources, logged::add);

        store.ensureDefaultFiles();                       // boot: extract first
        Migrations.Result result = migrations().migrate(); // then the chain
        store.ensureDefaultFiles();                       // post-migration fill

        assertEquals(List.of(4, 5), result.stepsApplied());
        SnapshotParser.Result parsed = SnapshotParser.parse(store.loadSources());
        assertEquals("custom", parsed.snapshot().selectedEffectsPreset(),
                "the imported custom preset must be selected (4→5 never flips a custom selection)");
        me.vexmc.mental.v5.config.settings.HitFeedbackSettings feedback =
                (me.vexmc.mental.v5.config.settings.HitFeedbackSettings)
                        parsed.snapshot().settings(
                                me.vexmc.mental.v5.feature.Feature.HIT_FEEDBACK.settingsKey());
        assertEquals(SIGNATURE_HIT_SOUNDS, feedback.sounds(),
                "the server must sound exactly as it did on 2.5.2");
        DeathEffectsSettings death =
                (DeathEffectsSettings) parsed.snapshot().settings(
                        me.vexmc.mental.v5.feature.Feature.DEATH_EFFECTS.settingsKey());
        assertTrue(death.lightning(), "the signature death strike survives the upgrade");
        assertTrue(parsed.issues().isEmpty(), () -> "issues: " + parsed.issues());
        // The whole library is present for the GUI (vanilla retired in 2.5.5).
        assertTrue(parsed.snapshot().hasEffectsPreset("signature"));
        assertTrue(parsed.snapshot().hasEffectsPreset("custom"));
    }

    @Test
    void aStampedV3TreeWithoutOldEffectsJustStampsForward() throws Exception {
        // Nothing legacy on disk: the step only advances the stamp — no backups
        // of effects, no custom import, no effects.yml (extraction owns it).
        installFixture("v3");

        Migrations.Result result = migrations().migrate();

        assertEquals(List.of(4, 5), result.stepsApplied());
        assertEquals(5, load("config.yml").getInt("config-version"));
        assertFalse(Files.exists(dataFolder.resolve("config-backup-v3/effects")));
        assertFalse(Files.exists(dataFolder.resolve(ConfigStore.EFFECTS_PRESETS_DIR).resolve("custom.yml")));
        assertFalse(Files.exists(dataFolder.resolve(ConfigStore.EFFECTS_FILE)));
    }

    @Test
    void anUnstampedTreeWithOldEffectsFilesDetectsAsV3() throws Exception {
        // Belt-and-braces: a tree with no config-version stamp but the 2.5.2
        // effects layout must not read as already-current.
        Files.writeString(dataFolder.resolve("config.yml"), """
                modules:
                  hit-feedback: true
                """, StandardCharsets.UTF_8);
        writeOldEffectsFile("hit-feedback.yml", "hit-feedback:\n  preset: signature\n");

        assertEquals(3, migrations().detectVersion());

        migrations().migrate();
        EffectsPreset custom = importedCustom();
        assertEquals(SIGNATURE_HIT_SOUNDS, custom.hitFeedback().sounds());
    }

    /* ------------------------------------------------------------------ */
    /*  4 → 5 : the vanilla-preset retirement                              */
    /* ------------------------------------------------------------------ */

    private void installV4Tree(String effectsPreset) throws Exception {
        Files.writeString(dataFolder.resolve("config.yml"),
                "config-version: 4\nmodules:\n  hit-feedback: false\n", StandardCharsets.UTF_8);
        Files.writeString(dataFolder.resolve(ConfigStore.EFFECTS_FILE),
                "effects:\n  preset: " + effectsPreset + "\n", StandardCharsets.UTF_8);
    }

    private Path vanillaPreset() {
        return dataFolder.resolve(ConfigStore.EFFECTS_PRESETS_DIR).resolve("vanilla.yml");
    }

    @Test
    void v4RetiresThePristineVanillaPresetAndFlipsTheSelection() throws Exception {
        installV4Tree("vanilla");
        Files.createDirectories(vanillaPreset().getParent());
        Files.writeString(vanillaPreset(), pristineVanilla(), StandardCharsets.UTF_8);

        Migrations.Result result = migrations().migrate();

        assertEquals(4, result.fromVersion());
        assertEquals(5, result.toVersion());
        assertEquals(List.of(5), result.stepsApplied());
        assertFalse(Files.exists(vanillaPreset()), "the pristine vanilla.yml must be retired");
        assertTrue(Files.isRegularFile(
                        dataFolder.resolve("config-backup-v4/effects/presets/vanilla.yml")),
                "the retired vanilla.yml must be backed up");
        assertEquals("signature", load(ConfigStore.EFFECTS_FILE).getString("effects.preset"),
                "the selection flips from the retired vanilla to signature");
        assertEquals(5, load("config.yml").getInt("config-version"));

        // Idempotent: a second run reads version 5 and does nothing.
        Migrations.Result second = migrations().migrate();
        assertTrue(second.stepsApplied().isEmpty());
    }

    @Test
    void v4KeepsAnEditedVanillaPresetAndItsSelection() throws Exception {
        installV4Tree("vanilla");
        Files.createDirectories(vanillaPreset().getParent());
        String edited = pristineVanilla().replace("display-name: Vanilla", "display-name: My Vanilla");
        Files.writeString(vanillaPreset(), edited, StandardCharsets.UTF_8);

        migrations().migrate();

        assertEquals(edited, Files.readString(vanillaPreset(), StandardCharsets.UTF_8),
                "an edited vanilla.yml is an owner file — never touched");
        assertEquals("vanilla", load(ConfigStore.EFFECTS_FILE).getString("effects.preset"),
                "an edited vanilla.yml keeps its selection (still resolvable via directory discovery)");
        assertFalse(Files.exists(dataFolder.resolve("config-backup-v4/effects/presets/vanilla.yml")),
                "an edited file is not backed up — it stays in place");
    }

    @Test
    void v4FlipsAVanillaSelectionWhenNoVanillaFileExists() throws Exception {
        // A v4 tree that selected vanilla but never had the file on disk: the
        // selection still flips so the runtime resolves signature, not an unknown
        // name.
        installV4Tree("vanilla");

        migrations().migrate();

        assertEquals("signature", load(ConfigStore.EFFECTS_FILE).getString("effects.preset"));
    }

    @Test
    void v4LeavesANonVanillaSelectionUntouched() throws Exception {
        installV4Tree("custom");

        migrations().migrate();

        assertEquals("custom", load(ConfigStore.EFFECTS_FILE).getString("effects.preset"),
                "a non-vanilla selection is never flipped");
    }

    @Test
    void v4ScrubsAVanillaEffectsOverlaySelection() throws Exception {
        installV4Tree("signature");
        Path overrides = dataFolder.resolve(ConfigStore.STATE_DIR).resolve(ConfigStore.OVERRIDES_FILE);
        Files.createDirectories(overrides.getParent());
        Files.writeString(overrides, "effects:\n  preset: vanilla\n", StandardCharsets.UTF_8);

        migrations().migrate();

        YamlConfiguration overlay = load("state/overrides.yml");
        assertFalse(overlay.isSet("effects.preset"),
                "a machine-overlay vanilla selection is scrubbed when vanilla.yml is gone");
    }
}
