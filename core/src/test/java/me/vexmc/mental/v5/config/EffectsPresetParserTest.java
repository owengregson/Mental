package me.vexmc.mental.v5.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import me.vexmc.mental.v5.config.settings.DamageIndicatorsSettings;
import me.vexmc.mental.v5.config.settings.DeathEffectsSettings;
import me.vexmc.mental.v5.config.settings.HitFeedbackSettings;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.SettingsKey;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * The Combat Effects preset library — the knockback-profile model mirrored
 * (2.5.3): one preset = one {@code effects/presets/<name>.yml} file carrying
 * all three module sections, selected by {@code effects.yml}'s
 * {@code effects.preset}. Pins: {@code parse(empty)} equals every DEFAULTS
 * record (the era-exact no-op), each bundled preset file parses EXACTLY to its
 * in-code {@link EffectsPreset} constant (a regenerated preset can never
 * drift — the {@code ProfileParserTest} precedent), and selection resolves
 * overlay ?? file ?? default with a loud vanilla fallback on a bad name.
 */
class EffectsPresetParserTest {

    private static YamlConfiguration resource(String stem) {
        String classpath = ConfigStore.EFFECTS_PRESETS_DIR + "/" + stem + ".yml";
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                EffectsPresetParserTest.class.getClassLoader().getResourceAsStream(classpath),
                StandardCharsets.UTF_8));
    }

    private static Map<String, Configuration> bundledPresetSources() {
        Map<String, Configuration> sources = new TreeMap<>();
        for (String stem : ConfigStore.BUNDLED_EFFECTS_PRESETS) {
            sources.put(stem, resource(stem));
        }
        return sources;
    }

    @SuppressWarnings("unchecked")
    private static <S> S settings(Snapshot snapshot, Feature feature) {
        return snapshot.settings((SettingsKey<S>) feature.settingsKey());
    }

    /* -------------------------- parse(empty) == DEFAULTS -------------------------- */

    @Test
    void emptySectionsParseToEveryDefaultsRecord() {
        ConfigIssues issues = new ConfigIssues();
        EffectsPreset parsed = EffectsPresetParser.parse("bare", new YamlConfiguration(), issues);

        assertEquals(HitFeedbackSettings.DEFAULTS, parsed.hitFeedback(),
                "hit-feedback parse(empty) must equal DEFAULTS");
        assertEquals(DamageIndicatorsSettings.DEFAULTS, parsed.damageIndicators(),
                "damage-indicators parse(empty) must equal DEFAULTS");
        assertEquals(DeathEffectsSettings.DEFAULTS, parsed.deathEffects(),
                "death-effects parse(empty) must equal DEFAULTS");
        assertTrue(issues.all().isEmpty(), () -> "issues: " + issues.all());
    }

    @Test
    void defaultsAreTheVanillaTune() {
        // The era-exact no-op: DEFAULTS is exactly what the vanilla preset ships —
        // the era hurt sound (with the era pitch jitter applied at emit time), no
        // particles, no low-HP layer, the shipped indicator feel, and a strict
        // death-effects nothing.
        assertEquals(EffectsPreset.VANILLA.hitFeedback(), HitFeedbackSettings.DEFAULTS);
        assertEquals(EffectsPreset.VANILLA.damageIndicators(), DamageIndicatorsSettings.DEFAULTS);
        assertEquals(EffectsPreset.VANILLA.deathEffects(), DeathEffectsSettings.DEFAULTS);
        assertEquals(HitFeedbackSettings.VANILLA_SOUNDS, HitFeedbackSettings.DEFAULTS.sounds());
        assertTrue(HitFeedbackSettings.DEFAULTS.vanillaTune(),
                "the default sound set carries the era pitch jitter");
        assertTrue(DeathEffectsSettings.DEFAULTS.fireworkColors().isEmpty(),
                "vanilla death effects launch nothing");
    }

    /* ----------------------- bundled files parse to the constants ----------------------- */

    @Test
    void everyBundledPresetFileParsesExactlyToItsConstant() {
        for (Map.Entry<String, EffectsPreset> entry : EffectsPreset.BUNDLED.entrySet()) {
            String stem = entry.getKey();
            ConfigIssues issues = new ConfigIssues();
            EffectsPreset parsed = EffectsPresetParser.parse(stem, resource(stem), issues);
            assertEquals(entry.getValue(), parsed,
                    () -> "bundled effects/presets/" + stem + ".yml must parse to its constant");
            assertTrue(issues.all().isEmpty(),
                    () -> stem + " parsed with issues: " + issues.all());
        }
    }

    @Test
    void customIsAByteWiseCopyOfSignatureValues() {
        // The owner's editable preset starts at signature's VALUES (the directive):
        // identical settings records, its own name/description.
        assertEquals(EffectsPreset.SIGNATURE.hitFeedback(), EffectsPreset.CUSTOM.hitFeedback());
        assertEquals(EffectsPreset.SIGNATURE.damageIndicators(), EffectsPreset.CUSTOM.damageIndicators());
        assertEquals(EffectsPreset.SIGNATURE.deathEffects(), EffectsPreset.CUSTOM.deathEffects());
    }

    @Test
    void signatureCarriesTheFullOwnedTune() {
        EffectsPreset signature = EffectsPreset.SIGNATURE;
        assertEquals(HitFeedbackSettings.SIGNATURE_SOUNDS, signature.hitFeedback().sounds());
        assertEquals(HitFeedbackSettings.SIGNATURE_PARTICLES, signature.hitFeedback().particles());
        assertEquals(HitFeedbackSettings.SIGNATURE_LOW_HEALTH_SOUNDS,
                signature.hitFeedback().lowHealthSounds());
        assertTrue(signature.deathEffects().lightning(), "signature strikes the cosmetic bolt");
        assertEquals(DeathEffectsSettings.SIGNATURE_SOUNDS, signature.deathEffects().sounds());
        assertEquals(DeathEffectsSettings.SIGNATURE_FIREWORK_COLORS,
                signature.deathEffects().fireworkColors(),
                "white &f, yellow &e, gold &6 — the signature blast");
        assertTrue(signature.deathEffects().particles().isEmpty(),
                "the real colored blast replaced the dust approximation");
    }

    /* ------------------------- selection (overlay ?? file ?? default) ------------------------- */

    private static SnapshotParser.Result parseSelection(String effectsYaml,
            Map<String, Configuration> presets) throws Exception {
        YamlConfiguration effects = new YamlConfiguration();
        if (effectsYaml != null) {
            effects.loadFromString(effectsYaml);
        }
        YamlConfiguration empty = new YamlConfiguration();
        return SnapshotParser.parse(new ConfigStore.Sources(
                empty, new YamlConfiguration(), new YamlConfiguration(), new YamlConfiguration(),
                new YamlConfiguration(), new YamlConfiguration(), new YamlConfiguration(),
                effects, presets, Map.of()));
    }

    @Test
    void noSelectionResolvesToVanilla() throws Exception {
        SnapshotParser.Result result = parseSelection(null, bundledPresetSources());
        assertEquals("vanilla", result.snapshot().selectedEffectsPreset());
        assertEquals(HitFeedbackSettings.DEFAULTS,
                settings(result.snapshot(), Feature.HIT_FEEDBACK));
        assertEquals(DeathEffectsSettings.DEFAULTS,
                settings(result.snapshot(), Feature.DEATH_EFFECTS));
        assertTrue(result.issues().isEmpty(), () -> "issues: " + result.issues());
    }

    @Test
    void explicitSelectionResolvesTheNamedPresetIntoTheThreeSettings() throws Exception {
        SnapshotParser.Result result = parseSelection(
                "effects:\n  preset: signature\n", bundledPresetSources());
        Snapshot snapshot = result.snapshot();
        assertEquals("signature", snapshot.selectedEffectsPreset());
        assertEquals(EffectsPreset.SIGNATURE.hitFeedback(),
                settings(snapshot, Feature.HIT_FEEDBACK));
        assertEquals(EffectsPreset.SIGNATURE.damageIndicators(),
                settings(snapshot, Feature.DAMAGE_INDICATORS));
        assertEquals(EffectsPreset.SIGNATURE.deathEffects(),
                settings(snapshot, Feature.DEATH_EFFECTS));
        assertTrue(result.issues().isEmpty(), () -> "issues: " + result.issues());
    }

    @Test
    void unknownSelectionWarnsOnceAndVanillaStandsIn() throws Exception {
        SnapshotParser.Result result = parseSelection(
                "effects:\n  preset: sinature\n", bundledPresetSources());
        assertEquals("vanilla", result.snapshot().selectedEffectsPreset(),
                "a bad name must fall back to the era-exact no-op");
        assertEquals(HitFeedbackSettings.DEFAULTS,
                settings(result.snapshot(), Feature.HIT_FEEDBACK));
        assertEquals(1, result.issues().size(), () -> "issues: " + result.issues());
        assertTrue(result.issues().get(0).contains("sinature"), () -> result.issues().get(0));
    }

    @Test
    void aMissingVanillaFileStillResolvesTheInCodeVanilla() throws Exception {
        // No preset files loaded at all (a torn install): the selection falls back
        // to the in-code vanilla constant — never a crash, never silence about it.
        SnapshotParser.Result result = parseSelection(
                "effects:\n  preset: signature\n", Map.of());
        assertEquals("vanilla", result.snapshot().selectedEffectsPreset());
        assertEquals(HitFeedbackSettings.DEFAULTS,
                settings(result.snapshot(), Feature.HIT_FEEDBACK));
        assertEquals(DeathEffectsSettings.DEFAULTS,
                settings(result.snapshot(), Feature.DEATH_EFFECTS));
        assertEquals(1, result.issues().size(), () -> "issues: " + result.issues());
    }

    @Test
    void anOwnerEditedPresetFileParsesItsTunedValues() throws Exception {
        // Owner edits are sacred: a tuned preset file feeds the modules verbatim,
        // per-knob warn-and-fallback still applying.
        YamlConfiguration tuned = new YamlConfiguration();
        tuned.loadFromString("""
                display-name: Mine
                hit-feedback:
                  sounds:
                    - sound: block.anvil.land
                      volume: 0.5
                      pitch: 0.6
                  low-health-threshold-hearts: 6.0
                damage-indicators:
                  ring-radius: 1.2
                death-effects:
                  lightning: true
                  firework:
                    colors:
                      - ff00aa
                """);
        Map<String, Configuration> presets = new TreeMap<>(bundledPresetSources());
        presets.put("mine", tuned);
        SnapshotParser.Result result = parseSelection("effects:\n  preset: mine\n", presets);
        Snapshot snapshot = result.snapshot();
        assertEquals("mine", snapshot.selectedEffectsPreset());
        HitFeedbackSettings feedback = settings(snapshot, Feature.HIT_FEEDBACK);
        assertEquals(1, feedback.sounds().size());
        assertEquals("block.anvil.land", feedback.sounds().get(0).sound());
        assertEquals(6.0, feedback.lowHealthThresholdHearts(), 0.0);
        DamageIndicatorsSettings indicators = settings(snapshot, Feature.DAMAGE_INDICATORS);
        assertEquals(1.2, indicators.ringRadius(), 1e-9);
        DeathEffectsSettings death = settings(snapshot, Feature.DEATH_EFFECTS);
        assertTrue(death.lightning());
        assertEquals(java.util.List.of(0xFF00AA), death.fireworkColors());
        assertTrue(result.issues().isEmpty(), () -> "issues: " + result.issues());
    }

    /* --------------------------- knob-level warn-and-fallback --------------------------- */

    @Test
    void soundKnobsAreParseClampedToTheirBounds() throws Exception {
        // A volume above the ceiling and a pitch below the floor each clamp to
        // the nearest bound (per-entry numberClamped, one warn apiece).
        YamlConfiguration file = new YamlConfiguration();
        file.loadFromString("""
                hit-feedback:
                  sounds:
                    - sound: entity.player.hurt
                      volume: 99
                      pitch: 0.01
                """);
        EffectsPreset parsed = EffectsPresetParser.parse("clamped", file, new ConfigIssues());
        assertEquals(HitFeedbackSettings.MAX_VOLUME, parsed.hitFeedback().sounds().get(0).volume(), 1e-6);
        assertEquals(HitFeedbackSettings.MIN_PITCH, parsed.hitFeedback().sounds().get(0).pitch(), 1e-6);
    }

    @Test
    void indicatorLifetimeClampsHighWithAWarning() throws Exception {
        YamlConfiguration file = new YamlConfiguration();
        file.loadFromString("""
                damage-indicators:
                  lifetime-ticks: 500
                  ring-radius: 0.8
                """);
        ConfigIssues issues = new ConfigIssues();
        EffectsPreset parsed = EffectsPresetParser.parse("clamped", file, issues);
        assertEquals(DamageIndicatorsSettings.MAX_LIFETIME, parsed.damageIndicators().lifetimeTicks());
        assertEquals(0.8, parsed.damageIndicators().ringRadius(), 1e-9);
        assertEquals(1, issues.all().size(), () -> "issues: " + issues.all());
        assertTrue(issues.all().get(0).contains("lifetime-ticks"), () -> issues.all().get(0));
    }

    @Test
    void deathEffectsKnobsParseWithTheDustHexAndFireworkContracts() throws Exception {
        // A dust particle carries its RRGGBB hex color in the block field; the
        // firework colors parse from hex with a tolerated # prefix, config
        // order kept.
        YamlConfiguration file = new YamlConfiguration();
        file.loadFromString("""
                death-effects:
                  lightning: true
                  sounds:
                    - sound: entity.lightning_bolt.thunder
                      volume: 0.8
                      pitch: 1.4
                  particles:
                    - particle: dust
                      block: ff00aa
                      count-min: 4
                      count-max: 6
                      mode: spread
                      spread: {x: 0.4, y: 0.5, z: 0.4}
                  firework:
                    colors:
                      - ff00aa
                      - "#00ff55"
                """);
        ConfigIssues issues = new ConfigIssues();
        EffectsPreset parsed = EffectsPresetParser.parse("tuned", file, issues);
        DeathEffectsSettings death = parsed.deathEffects();
        assertTrue(death.lightning());
        assertEquals("entity.lightning_bolt.thunder", death.sounds().get(0).sound());
        assertEquals("ff00aa", death.particles().get(0).block(),
                "the dust hex color rides the block field");
        assertEquals(HitFeedbackSettings.Mode.SPREAD, death.particles().get(0).mode());
        assertEquals(0.5, death.particles().get(0).spreadY(), 1e-9);
        assertEquals(java.util.List.of(0xFF00AA, 0x00FF55), death.fireworkColors(),
                "firework colors parse from hex, # prefix tolerated, config order kept");
        assertTrue(issues.all().isEmpty(), () -> "issues: " + issues.all());
    }

    @Test
    void deathEffectsWithoutAFireworkBlockLaunchesNone() throws Exception {
        // Absent (or empty) firework: means NO firework — the block is opt-in.
        YamlConfiguration file = new YamlConfiguration();
        file.loadFromString("""
                death-effects:
                  lightning: true
                """);
        EffectsPreset parsed = EffectsPresetParser.parse("bare", file, new ConfigIssues());
        assertTrue(parsed.deathEffects().fireworkColors().isEmpty(), "no firework block, no firework");
    }

    @Test
    void fireworkSkipsMalformedHexWithOneWarning() throws Exception {
        YamlConfiguration file = new YamlConfiguration();
        file.loadFromString("""
                death-effects:
                  firework:
                    colors:
                      - ffaa00
                      - not-a-color
                """);
        ConfigIssues issues = new ConfigIssues();
        EffectsPreset parsed = EffectsPresetParser.parse("typo", file, issues);
        assertEquals(java.util.List.of(0xFFAA00), parsed.deathEffects().fireworkColors(),
                "the valid color survives");
        assertEquals(1, issues.all().size(), () -> "issues: " + issues.all());
        assertTrue(issues.all().get(0).contains("not-a-color"), () -> issues.all().get(0));
    }

    @Test
    void retiredConfigYmlEffectsSectionsAreNamedLoudlyAndIgnored() throws Exception {
        // A lingering pre-2.5.3 config.yml section is never honoured (its schema
        // died with the per-module preset knob) and never dropped in silence: one
        // loud line per section, per parse — the values live in
        // effects/presets/custom.yml since the migration imported them.
        YamlConfiguration main = new YamlConfiguration();
        main.loadFromString("""
                hit-feedback:
                  preset: signature
                death-effects:
                  preset: signature
                """);
        SnapshotParser.Result result = SnapshotParser.parse(new ConfigStore.Sources(
                main, new YamlConfiguration(), new YamlConfiguration(), new YamlConfiguration(),
                new YamlConfiguration(), new YamlConfiguration(), new YamlConfiguration(),
                new YamlConfiguration(), bundledPresetSources(), Map.of()));
        assertEquals(HitFeedbackSettings.DEFAULTS,
                settings(result.snapshot(), Feature.HIT_FEEDBACK),
                "the retired section must not steer the parsed settings");
        assertEquals(2, result.issues().size(), () -> "issues: " + result.issues());
        assertTrue(result.issues().stream().allMatch(issue -> issue.contains("retired")),
                () -> "issues: " + result.issues());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.contains("hit-feedback")),
                () -> "issues: " + result.issues());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.contains("death-effects")),
                () -> "issues: " + result.issues());
    }
}
