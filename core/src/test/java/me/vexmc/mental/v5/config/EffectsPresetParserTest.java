package me.vexmc.mental.v5.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import me.vexmc.mental.v5.config.settings.DamageIndicatorsSettings;
import me.vexmc.mental.v5.config.settings.DeathEffectsSettings;
import me.vexmc.mental.v5.config.settings.HitFeedbackSettings;
import me.vexmc.mental.v5.config.settings.HitFeedbackSettings.Mode;
import me.vexmc.mental.v5.config.settings.HitFeedbackSettings.ParticleSpec;
import me.vexmc.mental.v5.config.settings.HitFeedbackSettings.SoundSpec;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.SettingsKey;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * The Combat Effects preset library — the knockback-profile model mirrored and
 * completed (2.5.5): one preset = one {@code effects/presets/<name>.yml} file
 * carrying all three module sections, selected by {@code effects.yml}'s
 * {@code effects.preset}, with the bundled YAML as the tune's source of truth
 * (there are no in-code preset value constants anymore). The drift pins now
 * live HERE as test-local expected records: the bundled signature.yml must
 * still parse to {@link #SIGNATURE_HIT}/{@link #SIGNATURE_INDICATORS}/{@link
 * #SIGNATURE_DEATH}, custom.yml must parse value-identical to it, and
 * {@code parse(empty)} must equal every DEFAULTS record (the era-exact no-op).
 * Selection resolves overlay ?? file ?? default with a loud SIGNATURE fallback
 * on a bad name.
 */
class EffectsPresetParserTest {

    /* ------------------- test-local drift pins (the signature tune) ------------------- */

    /** The signature hit-feedback tune — the layered chord, redstone burst, glow-squid low-HP chirp, 35%. */
    private static final HitFeedbackSettings SIGNATURE_HIT = new HitFeedbackSettings(
            List.of(
                    new SoundSpec("block.lodestone.break", 1.0f, 1.0f),
                    new SoundSpec("entity.generic.hurt", 0.85f, 0.75f),
                    new SoundSpec("entity.breeze.deflect", 0.75f, 1.15f)),
            List.of(new ParticleSpec("block", "redstone_block", 6, 8, Mode.EMANATE, 0.15f, 0, 0, 0)),
            List.of(new SoundSpec("entity.glow_squid.hurt", 0.9f, 1.2f)),
            35.0);

    /** The signature indicator tune — the shipped feel, the 3-tick roll hold, plus the green healing number. */
    private static final DamageIndicatorsSettings SIGNATURE_INDICATORS = new DamageIndicatorsSettings(
            40, 0.6, 0.3, 0.25, 0.06, 0.05, 0.98,
            "&f-{HEALTH} &c❤&r",
            "&c&l** -{HEALTH} ❤ **",
            25.0,
            3,
            "&a+{HEALTH} &c❤&r");

    /** The signature death strike — the cosmetic bolt, glow-squid death call, white/yellow/gold blast, kill title. */
    private static final DeathEffectsSettings SIGNATURE_DEATH = new DeathEffectsSettings(
            true,
            List.of(new SoundSpec("entity.glow_squid.death", 1.0f, 0.95f)),
            List.of(),
            List.of(0xFFFFFF, 0xFFFF55, 0xFFAA00),
            new DeathEffectsSettings.KillTitle(
                    "&c&lKILLED:&r &f{NAME}&r",
                    "&c➥&r &7This player's drops are protected for &r&f&n15s&r&7!",
                    5, 40, 10));

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
        // The era-exact no-op: DEFAULTS is exactly the audibly-vanilla tune —
        // the era hurt sound (with the era pitch jitter applied at emit time), no
        // particles, no low-HP layer, the shipped indicator feel with NO healing
        // indicator, and a strict death-effects nothing. There is no "vanilla"
        // preset anymore; the vanilla feel is "leave the modules off".
        assertEquals(HitFeedbackSettings.VANILLA_SOUNDS, HitFeedbackSettings.DEFAULTS.sounds());
        assertTrue(HitFeedbackSettings.DEFAULTS.vanillaTune(),
                "the default sound set carries the era pitch jitter");
        assertTrue(HitFeedbackSettings.DEFAULTS.particles().isEmpty(), "vanilla pops no particles");
        assertTrue(HitFeedbackSettings.DEFAULTS.lowHealthSounds().isEmpty(), "vanilla layers nothing");
        assertEquals(35.0, HitFeedbackSettings.DEFAULTS.lowHealthThresholdPercent(), 0.0,
                "the default low-health threshold is 35% of max health (inert while the layer is empty)");
        assertEquals("", DamageIndicatorsSettings.DEFAULTS.healText(),
                "no healing indicator by default — the era-exact no-op");
        assertTrue(DeathEffectsSettings.DEFAULTS.fireworkColors().isEmpty(),
                "vanilla death effects launch nothing");
    }

    /* ----------------------- bundled files parse to the drift pins ----------------------- */

    @Test
    void signatureFileParsesToTheExpectedRecords() {
        // The signature.yml resource is the tune's source of truth; it must parse
        // to the test-local pins exactly (percent 35 + the heal-text), so a
        // regenerated file can never drift silently.
        ConfigIssues issues = new ConfigIssues();
        EffectsPreset signature = EffectsPresetParser.parse("signature", resource("signature"), issues);
        assertEquals(SIGNATURE_HIT, signature.hitFeedback(), "signature hit-feedback drift");
        assertEquals(SIGNATURE_INDICATORS, signature.damageIndicators(), "signature indicators drift");
        assertEquals(SIGNATURE_DEATH, signature.deathEffects(), "signature death-effects drift");
        assertEquals(35.0, signature.hitFeedback().lowHealthThresholdPercent(), 0.0,
                "signature fires the low-health layer below 35% of max health");
        assertEquals("&a+{HEALTH} &c❤&r", signature.damageIndicators().healText(),
                "signature shows the green healing indicator");
        assertTrue(issues.all().isEmpty(), () -> "signature parsed with issues: " + issues.all());
    }

    @Test
    void customFileParsesValueIdenticalToSignature() {
        // The owner's editable preset starts at signature's VALUES (the directive):
        // identical settings records, only its own name/description differ.
        ConfigIssues issues = new ConfigIssues();
        EffectsPreset custom = EffectsPresetParser.parse("custom", resource("custom"), issues);
        assertEquals(SIGNATURE_HIT, custom.hitFeedback(), "custom hit-feedback must copy signature");
        assertEquals(SIGNATURE_INDICATORS, custom.damageIndicators(), "custom indicators must copy signature");
        assertEquals(SIGNATURE_DEATH, custom.deathEffects(), "custom death-effects must copy signature");
        assertTrue(issues.all().isEmpty(), () -> "custom parsed with issues: " + issues.all());
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
                new YamlConfiguration(),
                effects, presets, Map.of()));
    }

    @Test
    void noSelectionResolvesToSignature() throws Exception {
        // No effects.yml selection at all → the default is signature, and with no
        // preset files loaded the in-code FALLBACK stands in (DEFAULTS) — clean.
        SnapshotParser.Result result = parseSelection(null, Map.of());
        assertEquals("signature", result.snapshot().selectedEffectsPreset());
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
        assertEquals(SIGNATURE_HIT, settings(snapshot, Feature.HIT_FEEDBACK));
        assertEquals(SIGNATURE_INDICATORS, settings(snapshot, Feature.DAMAGE_INDICATORS));
        assertEquals(SIGNATURE_DEATH, settings(snapshot, Feature.DEATH_EFFECTS));
        assertTrue(result.issues().isEmpty(), () -> "issues: " + result.issues());
    }

    @Test
    void anEffectsOverlayFieldOverridesThePresetValuePerField() throws Exception {
        // Simulate what Overlay.apply does at load: set per-field overrides on
        // the effects.yml root under effects.<module>.<field>. The parser layers
        // them over the selected preset — overridden fields win, the rest keep
        // the preset value (effective = overlay ?? preset ?? default).
        YamlConfiguration effects = new YamlConfiguration();
        effects.loadFromString("effects:\n  preset: signature\n");
        effects.set("effects.death.kill-title", "&aOVERRIDDEN {NAME}");
        effects.set("effects.death.title-stay", 80);
        effects.set("effects.death.lightning", false);
        effects.set("effects.indicators.text", "&e{HEALTH}");
        SnapshotParser.Result result = SnapshotParser.parse(new ConfigStore.Sources(
                new YamlConfiguration(), new YamlConfiguration(), new YamlConfiguration(),
                new YamlConfiguration(), new YamlConfiguration(), new YamlConfiguration(),
                new YamlConfiguration(), new YamlConfiguration(),
                effects, bundledPresetSources(), Map.of()));
        Snapshot snapshot = result.snapshot();

        DeathEffectsSettings death = settings(snapshot, Feature.DEATH_EFFECTS);
        assertEquals("&aOVERRIDDEN {NAME}", death.killTitle().title(), "the overridden title wins");
        assertEquals(80, death.killTitle().stay(), "the overridden timing wins");
        assertEquals("&c➥&r &7This player's drops are protected for &r&f&n15s&r&7!",
                death.killTitle().subtitle(), "an un-overridden field keeps the preset value");
        assertFalse(death.lightning(), "the lightning override wins over the signature's true");

        DamageIndicatorsSettings indicators = settings(snapshot, Feature.DAMAGE_INDICATORS);
        assertEquals("&e{HEALTH}", indicators.text(), "the indicator text override wins");
        assertEquals("&c&l** -{HEALTH} ❤ **", indicators.critText(), "un-overridden crit-text keeps the preset");
        assertTrue(result.issues().isEmpty(), () -> "issues: " + result.issues());
    }

    @Test
    void unknownSelectionWarnsOnceAndSignatureStandsIn() throws Exception {
        SnapshotParser.Result result = parseSelection(
                "effects:\n  preset: sinature\n", bundledPresetSources());
        assertEquals("signature", result.snapshot().selectedEffectsPreset(),
                "a bad name must fall back to the signature default");
        assertEquals(SIGNATURE_HIT, settings(result.snapshot(), Feature.HIT_FEEDBACK));
        assertEquals(1, result.issues().size(), () -> "issues: " + result.issues());
        assertTrue(result.issues().get(0).contains("sinature"), () -> result.issues().get(0));
        assertTrue(result.issues().get(0).contains("signature preset stands in"),
                () -> result.issues().get(0));
    }

    @Test
    void aRetiredVanillaSelectionFallsBackToSignatureLoudly() throws Exception {
        // The vanilla.yml file left the bundle in 2.5.5. A lingering
        // `preset: vanilla` that the 4→5 migration did not flip lands on the
        // unknown-name fallback: signature stands in, loudly.
        SnapshotParser.Result result = parseSelection(
                "effects:\n  preset: vanilla\n", bundledPresetSources());
        assertEquals("signature", result.snapshot().selectedEffectsPreset());
        assertEquals(1, result.issues().size(), () -> "issues: " + result.issues());
        assertTrue(result.issues().get(0).contains("vanilla"), () -> result.issues().get(0));
    }

    @Test
    void aMissingSignatureFileStillResolvesTheInCodeFallback() throws Exception {
        // No preset files loaded at all (a torn install): the selection falls back
        // to the in-code signature FALLBACK — never a crash, never silence about it.
        // (In production ConfigStore serves the JAR resource before this point; the
        // FALLBACK is the last-ditch value for a truly empty source set.)
        SnapshotParser.Result result = parseSelection(
                "effects:\n  preset: signature\n", Map.of());
        assertEquals("signature", result.snapshot().selectedEffectsPreset());
        assertEquals(HitFeedbackSettings.DEFAULTS,
                settings(result.snapshot(), Feature.HIT_FEEDBACK));
        assertEquals(DeathEffectsSettings.DEFAULTS,
                settings(result.snapshot(), Feature.DEATH_EFFECTS));
        assertTrue(result.issues().isEmpty(),
                () -> "the default name is never an unknown-name warning: " + result.issues());
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
                  low-health-threshold-percent: 60
                damage-indicators:
                  ring-radius: 1.2
                  heal-text: "&a+{HEALTH}"
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
        assertEquals(60.0, feedback.lowHealthThresholdPercent(), 0.0);
        DamageIndicatorsSettings indicators = settings(snapshot, Feature.DAMAGE_INDICATORS);
        assertEquals(1.2, indicators.ringRadius(), 1e-9);
        assertEquals("&a+{HEALTH}", indicators.healText());
        DeathEffectsSettings death = settings(snapshot, Feature.DEATH_EFFECTS);
        assertTrue(death.lightning());
        assertEquals(List.of(0xFF00AA), death.fireworkColors());
        assertTrue(result.issues().isEmpty(), () -> "issues: " + result.issues());
    }

    /* --------------------------- the percent threshold (F2) --------------------------- */

    @Test
    void percentThresholdClampsToRangeWithAWarning() throws Exception {
        YamlConfiguration file = new YamlConfiguration();
        file.loadFromString("""
                hit-feedback:
                  low-health-threshold-percent: 140
                """);
        ConfigIssues issues = new ConfigIssues();
        EffectsPreset parsed = EffectsPresetParser.parse("clamped", file, issues);
        assertEquals(100.0, parsed.hitFeedback().lowHealthThresholdPercent(), 0.0,
                "percent clamps to [0, 100]");
        assertEquals(1, issues.all().size(), () -> "issues: " + issues.all());
        assertTrue(issues.all().get(0).contains("low-health-threshold-percent"),
                () -> issues.all().get(0));
    }

    @Test
    void legacyHeartsKeyWarnsOnceAndConvertsToPercent() throws Exception {
        // The 2.5.2 low-health-threshold-hearts is retired; a lingering key (with
        // no percent key) is honoured with one warn and converted hearts × 10 —
        // never silently dropped (mandate B10).
        YamlConfiguration file = new YamlConfiguration();
        file.loadFromString("""
                hit-feedback:
                  low-health-threshold-hearts: 6.0
                """);
        ConfigIssues issues = new ConfigIssues();
        EffectsPreset parsed = EffectsPresetParser.parse("legacy", file, issues);
        assertEquals(60.0, parsed.hitFeedback().lowHealthThresholdPercent(), 0.0,
                "6 hearts of a 20-max player = 60% of max health");
        assertEquals(1, issues.all().size(), () -> "issues: " + issues.all());
        assertTrue(issues.all().get(0).contains("low-health-threshold-hearts"),
                () -> issues.all().get(0));
    }

    @Test
    void bothThresholdKeysPercentWinsWithANotice() throws Exception {
        // Both present ⇒ the explicit percent wins and the retired hearts key is
        // noted once — never a silent double-read.
        YamlConfiguration file = new YamlConfiguration();
        file.loadFromString("""
                hit-feedback:
                  low-health-threshold-percent: 20
                  low-health-threshold-hearts: 6.0
                """);
        ConfigIssues issues = new ConfigIssues();
        EffectsPreset parsed = EffectsPresetParser.parse("both", file, issues);
        assertEquals(20.0, parsed.hitFeedback().lowHealthThresholdPercent(), 0.0,
                "the explicit percent wins over the retired hearts key");
        assertEquals(1, issues.all().size(), () -> "issues: " + issues.all());
        assertTrue(issues.all().get(0).contains("percent wins"), () -> issues.all().get(0));
    }

    /* --------------------------- the crit-marker percent threshold (2.7.1) --------------------------- */

    @Test
    void critThresholdPercentClampsToRangeWithAWarning() throws Exception {
        YamlConfiguration file = new YamlConfiguration();
        file.loadFromString("""
                damage-indicators:
                  crit-threshold-percent: 140
                """);
        ConfigIssues issues = new ConfigIssues();
        EffectsPreset parsed = EffectsPresetParser.parse("clamped", file, issues);
        assertEquals(100.0, parsed.damageIndicators().critThresholdPercent(), 0.0,
                "percent clamps to [0, 100]");
        assertEquals(1, issues.all().size(), () -> "issues: " + issues.all());
        assertTrue(issues.all().get(0).contains("crit-threshold-percent"), () -> issues.all().get(0));
    }

    @Test
    void legacyCritHeartsKeyWarnsOnceAndConvertsToPercent() throws Exception {
        // The pre-2.7.1 absolute crit-threshold-hearts is retired; a lingering key
        // (with no percent key) is honoured with one warn and converted hearts × 10
        // — never silently dropped (mandate B10), the low-health precedent.
        YamlConfiguration file = new YamlConfiguration();
        file.loadFromString("""
                damage-indicators:
                  crit-threshold-hearts: 5.0
                """);
        ConfigIssues issues = new ConfigIssues();
        EffectsPreset parsed = EffectsPresetParser.parse("legacy", file, issues);
        assertEquals(50.0, parsed.damageIndicators().critThresholdPercent(), 0.0,
                "5 hearts of a 20-max player = 50% of max health");
        assertEquals(1, issues.all().size(), () -> "issues: " + issues.all());
        assertTrue(issues.all().get(0).contains("crit-threshold-hearts"), () -> issues.all().get(0));
    }

    @Test
    void bothCritThresholdKeysPercentWinsWithANotice() throws Exception {
        // Both present ⇒ the explicit percent wins and the retired hearts key is
        // noted once — never a silent double-read.
        YamlConfiguration file = new YamlConfiguration();
        file.loadFromString("""
                damage-indicators:
                  crit-threshold-percent: 30
                  crit-threshold-hearts: 5.0
                """);
        ConfigIssues issues = new ConfigIssues();
        EffectsPreset parsed = EffectsPresetParser.parse("both", file, issues);
        assertEquals(30.0, parsed.damageIndicators().critThresholdPercent(), 0.0,
                "the explicit percent wins over the retired hearts key");
        assertEquals(1, issues.all().size(), () -> "issues: " + issues.all());
        assertTrue(issues.all().get(0).contains("percent wins"), () -> issues.all().get(0));
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
        assertEquals(Mode.SPREAD, death.particles().get(0).mode());
        assertEquals(0.5, death.particles().get(0).spreadY(), 1e-9);
        assertEquals(List.of(0xFF00AA, 0x00FF55), death.fireworkColors(),
                "firework colors parse from hex, # prefix tolerated, config order kept");
        assertTrue(issues.all().isEmpty(), () -> "issues: " + issues.all());
    }

    @Test
    void deathEffectsKillTitleParsesWithColourCodesAndClampedTimings() throws Exception {
        // The kill-title block carries a coloured title/subtitle (codes ride the
        // raw string untouched) and three client-tick timings; a stay past the
        // ceiling clamps loud, an absent block keeps KillTitle.NONE.
        YamlConfiguration file = new YamlConfiguration();
        file.loadFromString("""
                death-effects:
                  kill-title:
                    title: "&c&lKILLED:&r &f{NAME}&r"
                    subtitle: "&7protected for {PROTECT_SECONDS}s"
                    fade-in: 5
                    stay: 999
                    fade-out: 10
                """);
        ConfigIssues issues = new ConfigIssues();
        EffectsPreset parsed = EffectsPresetParser.parse("titled", file, issues);
        DeathEffectsSettings.KillTitle title = parsed.deathEffects().killTitle();
        assertEquals("&c&lKILLED:&r &f{NAME}&r", title.title());
        assertEquals("&7protected for {PROTECT_SECONDS}s", title.subtitle());
        assertEquals(5, title.fadeIn());
        assertEquals(400, title.stay(), "stay clamps to the 400-tick ceiling");
        assertEquals(10, title.fadeOut());
        assertTrue(title.present(), "text is set — a title is sent");
        assertEquals(1, issues.all().size(), () -> "issues: " + issues.all());
        assertTrue(issues.all().get(0).contains("stay"), () -> issues.all().get(0));
    }

    @Test
    void deathEffectsWithNoKillTitleBlockIsTitleless() throws Exception {
        YamlConfiguration file = new YamlConfiguration();
        file.loadFromString("death-effects:\n  lightning: true\n");
        EffectsPreset parsed = EffectsPresetParser.parse("bare", file, new ConfigIssues());
        assertEquals(DeathEffectsSettings.KillTitle.NONE, parsed.deathEffects().killTitle());
        assertTrue(!parsed.deathEffects().killTitle().present(), "no text — sends nothing");
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
        assertEquals(List.of(0xFFAA00), parsed.deathEffects().fireworkColors(),
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
        // Select a distinct DEFAULTS-valued preset (an empty file) so the assertion
        // proves the retired config.yml section did NOT steer the tune — with
        // signature as the default now, an unselected parse would already be the
        // signature tune and could not tell the two apart.
        YamlConfiguration effects = new YamlConfiguration();
        effects.loadFromString("effects:\n  preset: muted\n");
        Map<String, Configuration> presets = new TreeMap<>(bundledPresetSources());
        presets.put("muted", new YamlConfiguration());
        SnapshotParser.Result result = SnapshotParser.parse(new ConfigStore.Sources(
                main, new YamlConfiguration(), new YamlConfiguration(), new YamlConfiguration(),
                new YamlConfiguration(), new YamlConfiguration(), new YamlConfiguration(),
                new YamlConfiguration(),
                effects, presets, Map.of()));
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
