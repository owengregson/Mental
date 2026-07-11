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

    private Migrations migrations() {
        return new Migrations(dataFolder, resources, logged::add);
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
        assertEquals(4, result.toVersion());
        assertEquals(List.of(2, 3, 4), result.stepsApplied());

        // Both step backups exist; the version is now stamped 4.
        assertTrue(Files.isRegularFile(dataFolder.resolve("config-backup-v1/config.yml")));
        assertTrue(Files.isRegularFile(dataFolder.resolve("config-backup-v2/config.yml")));
        assertTrue(Files.isRegularFile(dataFolder.resolve("state/overrides.yml")));

        YamlConfiguration main = load("config.yml");
        assertFalse(Migrations.isLegacyLayout(main), "migrated config.yml still legacy-shaped");
        assertEquals(4, main.getInt("config-version"));
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
        assertEquals(4, first.toVersion());
        assertEquals(List.of(3, 4), first.stepsApplied());
        assertTrue(Files.isRegularFile(dataFolder.resolve("config-backup-v2/config.yml")));
        assertTrue(Files.isRegularFile(dataFolder.resolve("state/overrides.yml")));
        assertEquals(4, load("config.yml").getInt("config-version"));

        // Idempotent: a second run reads version 4 and does nothing.
        byte[] afterFirst = Files.readAllBytes(dataFolder.resolve("config.yml"));
        Migrations.Result second = migrations().migrate();
        assertEquals(4, second.fromVersion());
        assertTrue(second.stepsApplied().isEmpty());
        assertEquals(-1, java.util.Arrays.mismatch(afterFirst,
                Files.readAllBytes(dataFolder.resolve("config.yml"))), "config.yml unchanged on re-run");
    }

    @Test
    void freshTreeAndAlreadyMigratedTreeAreNoOps() throws Exception {
        // No config.yml on disk → nothing to migrate.
        Migrations.Result fresh = migrations().migrate();
        assertEquals(4, fresh.fromVersion());
        assertTrue(fresh.stepsApplied().isEmpty());
        assertFalse(Files.exists(dataFolder.resolve("config-backup-v1")));
        assertFalse(Files.exists(dataFolder.resolve("config-backup-v2")));

        // An already-v4 tree is untouched.
        installFixture("v4");
        byte[] before = Files.readAllBytes(dataFolder.resolve("config.yml"));
        Migrations.Result already = migrations().migrate();
        assertEquals(4, already.fromVersion());
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
        assertEquals(4, second.fromVersion());
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
        assertEquals(4, result.toVersion());
        assertEquals(List.of(4), result.stepsApplied());
        assertEquals(4, load("config.yml").getInt("config-version"));

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
        assertEquals(HitFeedbackSettings.SIGNATURE_SOUNDS, custom.hitFeedback().sounds());
        assertEquals(HitFeedbackSettings.SIGNATURE_LOW_HEALTH_SOUNDS, custom.hitFeedback().lowHealthSounds());
        assertEquals(0.9, custom.damageIndicators().ringRadius(), 1e-9,
                "the tuned indicator knob imports verbatim");
        assertTrue(custom.deathEffects().lightning(), "the signature death strike imports");
        assertEquals(DeathEffectsSettings.SIGNATURE_FIREWORK_COLORS, custom.deathEffects().fireworkColors());

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
        assertEquals(6.0, custom.hitFeedback().lowHealthThresholdHearts(), 0.0);
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
        assertEquals(HitFeedbackSettings.DEFAULTS, custom.hitFeedback());
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

        assertEquals(List.of(4), result.stepsApplied());
        EffectsPreset custom = importedCustom();
        assertEquals(HitFeedbackSettings.SIGNATURE_SOUNDS, custom.hitFeedback().sounds());
        assertEquals("custom", load(ConfigStore.EFFECTS_FILE).getString("effects.preset"));
        assertTrue(logged.stream().anyMatch(line ->
                        line.contains("config.yml") && line.contains("hit-feedback")),
                () -> "expected a loud config.yml-section line, logged: " + logged);
    }

    @Test
    void importIsSuppressedWhenCustomAlreadyExistsAndVanillaStaysSelected() throws Exception {
        // The import IS custom.yml's extraction — an already-present custom.yml is
        // an owner file and must never be overwritten; without an import the
        // selection stays vanilla (selecting unknown custom values could change
        // the sound silently) and the skip is reported loudly.
        installV3Config();
        writeOldEffectsFile("hit-feedback.yml", "hit-feedback:\n  preset: signature\n");
        Path custom = dataFolder.resolve(ConfigStore.EFFECTS_PRESETS_DIR).resolve("custom.yml");
        Files.createDirectories(custom.getParent());
        Files.writeString(custom, "display-name: Mine\n", StandardCharsets.UTF_8);

        migrations().migrate();

        assertEquals("display-name: Mine\n", Files.readString(custom, StandardCharsets.UTF_8),
                "an existing custom.yml is sacred — never overwritten by the import");
        assertEquals("vanilla", load(ConfigStore.EFFECTS_FILE).getString("effects.preset"),
                "no import → no custom selection");
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

        assertEquals(List.of(4), result.stepsApplied());
        SnapshotParser.Result parsed = SnapshotParser.parse(store.loadSources());
        assertEquals("custom", parsed.snapshot().selectedEffectsPreset(),
                "the imported custom preset must be selected");
        me.vexmc.mental.v5.config.settings.HitFeedbackSettings feedback =
                (me.vexmc.mental.v5.config.settings.HitFeedbackSettings)
                        parsed.snapshot().settings(
                                me.vexmc.mental.v5.feature.Feature.HIT_FEEDBACK.settingsKey());
        assertEquals(HitFeedbackSettings.SIGNATURE_SOUNDS, feedback.sounds(),
                "the server must sound exactly as it did on 2.5.2");
        DeathEffectsSettings death =
                (DeathEffectsSettings) parsed.snapshot().settings(
                        me.vexmc.mental.v5.feature.Feature.DEATH_EFFECTS.settingsKey());
        assertTrue(death.lightning(), "the signature death strike survives the upgrade");
        assertTrue(parsed.issues().isEmpty(), () -> "issues: " + parsed.issues());
        // The whole library is present for the GUI.
        assertTrue(parsed.snapshot().hasEffectsPreset("vanilla"));
        assertTrue(parsed.snapshot().hasEffectsPreset("signature"));
        assertTrue(parsed.snapshot().hasEffectsPreset("custom"));
    }

    @Test
    void aStampedV3TreeWithoutOldEffectsJustStampsForward() throws Exception {
        // Nothing legacy on disk: the step only advances the stamp — no backups
        // of effects, no custom import, no effects.yml (extraction owns it).
        installFixture("v3");

        Migrations.Result result = migrations().migrate();

        assertEquals(List.of(4), result.stepsApplied());
        assertEquals(4, load("config.yml").getInt("config-version"));
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
        assertEquals(HitFeedbackSettings.SIGNATURE_SOUNDS, custom.hitFeedback().sounds());
    }
}
