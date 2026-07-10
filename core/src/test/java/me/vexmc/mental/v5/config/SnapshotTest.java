package me.vexmc.mental.v5.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.v5.config.settings.ComboSettings;
import me.vexmc.mental.v5.config.settings.CompensationSettings;
import me.vexmc.mental.v5.config.settings.CraftingSettings;
import me.vexmc.mental.v5.config.settings.DamageIndicatorsSettings;
import me.vexmc.mental.v5.config.settings.DeathEffectsSettings;
import me.vexmc.mental.v5.config.settings.FastPotsSettings;
import me.vexmc.mental.v5.config.settings.FishingKnockbackSettings;
import me.vexmc.mental.v5.config.settings.HitFeedbackSettings;
import me.vexmc.mental.v5.config.settings.HitRegSettings;
import me.vexmc.mental.v5.config.settings.NoSettings;
import me.vexmc.mental.v5.config.settings.OffhandSettings;
import me.vexmc.mental.v5.config.settings.PotFillSettings;
import me.vexmc.mental.v5.config.settings.ProjectileKnockbackSettings;
import me.vexmc.mental.v5.config.settings.ReachHandicapSettings;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.SettingsKey;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * The typed, descriptor-keyed snapshot: {@code parse(empty) == full defaults}
 * (the era-exact no-op pin extended to every feature), one named issue per
 * wrong-typed knob with a per-key fallback (the retired MentalConfigTest
 * warn-behavior pins), and structural immutability.
 */
class SnapshotTest {

    private static YamlConfiguration yaml(String content) throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(content);
        return configuration;
    }

    private static SnapshotParser.Result parse(
            String main, String knockback, String hitReg, String compensation) throws Exception {
        return SnapshotParser.parse(
                yaml(main), yaml(knockback), yaml(hitReg), yaml(compensation), Map.of());
    }

    @SuppressWarnings("unchecked")
    private static <S> S settings(Snapshot snapshot, Feature feature) {
        return snapshot.settings((SettingsKey<S>) feature.settingsKey());
    }

    @Test
    void emptySourcesYieldEveryDefaultWithoutIssues() throws Exception {
        SnapshotParser.Result result = parse("", "", "", "");
        Snapshot snapshot = result.snapshot();

        assertTrue(result.issues().isEmpty(), () -> "unexpected issues: " + result.issues());

        // Every feature sits at its descriptor default enablement.
        for (Feature feature : Feature.values()) {
            assertEquals(feature.defaultEnabled(), snapshot.enabled(feature),
                    () -> feature + " default enablement");
        }

        // Every settings record equals its DEFAULTS.
        assertEquals(HitRegSettings.DEFAULTS, settings(snapshot, Feature.HIT_REGISTRATION));
        assertEquals(CompensationSettings.DEFAULTS, settings(snapshot, Feature.LATENCY_COMPENSATION));
        assertEquals(FishingKnockbackSettings.DEFAULTS, settings(snapshot, Feature.FISHING_KNOCKBACK));
        assertEquals(ProjectileKnockbackSettings.DEFAULTS, settings(snapshot, Feature.PROJECTILE_KNOCKBACK));
        assertEquals(CraftingSettings.DEFAULTS, settings(snapshot, Feature.CRAFTING));
        assertEquals(OffhandSettings.DEFAULTS, settings(snapshot, Feature.OFFHAND));
        ComboSettings combo = settings(snapshot, Feature.COMBO_HOLD);
        assertEquals(ComboSettings.DEFAULTS, combo);
        // The STATIC fallback separation default is 2.85 (the lab's held-separation
        // equilibrium); the default target mode is the geometric BOUNDARY.
        assertEquals(2.85, combo.staticTarget(), 0.0, "parse-empty combo static target is 2.85");
        assertEquals(PotFillSettings.DEFAULTS, settings(snapshot, Feature.POT_FILL));
        assertEquals(FastPotsSettings.DEFAULTS, settings(snapshot, Feature.FAST_POTS));
        // The FEEDBACK family: both cosmetic records default to their era-exact no-op.
        assertEquals(HitFeedbackSettings.DEFAULTS, settings(snapshot, Feature.HIT_FEEDBACK));
        // The low-health extra layer defaults to no layer / a 4-heart post-hit ceiling.
        HitFeedbackSettings feedback = settings(snapshot, Feature.HIT_FEEDBACK);
        assertTrue(feedback.lowHealthSounds().isEmpty(), "no low-health layer by default (VANILLA)");
        assertEquals(4.0, feedback.lowHealthThresholdHearts(), 0.0, "default low-health threshold is 4 hearts");
        assertEquals(DamageIndicatorsSettings.DEFAULTS, settings(snapshot, Feature.DAMAGE_INDICATORS));
        assertEquals(DeathEffectsSettings.DEFAULTS, settings(snapshot, Feature.DEATH_EFFECTS));
        // The VANILLA death preset is a strict nothing — no lightning, no sounds,
        // no particles (the module toggle owns zero-touch; enabled-but-vanilla is
        // a no-op by construction).
        DeathEffectsSettings death = settings(snapshot, Feature.DEATH_EFFECTS);
        assertFalse(death.lightning(), "vanilla death preset strikes no lightning");
        assertTrue(death.sounds().isEmpty(), "vanilla death preset plays nothing");
        assertTrue(death.particles().isEmpty(), "vanilla death preset pops nothing");
        // Toggle-only features share the NoSettings singleton default.
        for (Feature feature : Feature.values()) {
            if (feature.settingsKey().type() == NoSettings.class) {
                assertSame(NoSettings.DEFAULTS, settings(snapshot, feature),
                        () -> feature + " must carry NoSettings.DEFAULTS");
            }
        }

        // General sections default; the profile resolves to LEGACY_17 for any world.
        assertEquals(AnticheatModeDefault(), snapshot.anticheat().mode());
        assertFalse(snapshot.debug().enabled());
        assertEquals(KnockbackProfile.LEGACY_17, snapshot.profileFor("anything"));
        assertEquals(KnockbackProfile.LEGACY_17, snapshot.profileFor("world_nether"));
        // bStats is on by default; the absent `metrics` section reads true without an issue.
        assertTrue(snapshot.metricsEnabled());
    }

    private static AnticheatMode AnticheatModeDefault() {
        return AnticheatMode.AUTO;
    }

    @Test
    void metricsToggleReadsFromTheConfig() throws Exception {
        SnapshotParser.Result off = parse("""
                metrics:
                  enabled: false
                """, "", "", "");
        assertTrue(off.issues().isEmpty(), () -> "unexpected issues: " + off.issues());
        assertFalse(off.snapshot().metricsEnabled());

        // Explicit true and an absent section both read true.
        assertTrue(parse("metrics:\n  enabled: true\n", "", "", "").snapshot().metricsEnabled());
        assertTrue(parse("", "", "", "").snapshot().metricsEnabled());
    }

    @Test
    void moduleTogglesFlipFromTheModulesMap() throws Exception {
        Snapshot snapshot = parse("""
                modules:
                  knockback: false
                  wtap-registration: false
                  old-critical-hits: true
                  sword-blocking: true
                """, "", "", "").snapshot();

        assertFalse(snapshot.enabled(Feature.KNOCKBACK));
        assertFalse(snapshot.enabled(Feature.WTAP_REGISTRATION));
        assertTrue(snapshot.enabled(Feature.CRIT_FALLBACK));
        assertTrue(snapshot.enabled(Feature.SWORD_BLOCKING));
        // Untouched features keep their defaults.
        assertTrue(snapshot.enabled(Feature.HIT_REGISTRATION));
        assertFalse(snapshot.enabled(Feature.HITBOX));
        // Infrastructure is always on.
        assertTrue(snapshot.enabled(Feature.ANTICHEAT_COMPAT));
    }

    @Test
    void wrongTypedKnobWarnsOnceAndFallsBack() throws Exception {
        SnapshotParser.Result result = parse("", "", """
                hit-registration:
                  max-cps: "lots"
                """, "");

        HitRegSettings hitReg = settings(result.snapshot(), Feature.HIT_REGISTRATION);
        assertEquals(HitRegSettings.DEFAULTS.maxCps(), hitReg.maxCps(), "fell back to the default");
        assertEquals(1, result.issues().size(), () -> "issues: " + result.issues());
        assertTrue(result.issues().get(0).contains("max-cps"));
        assertTrue(result.issues().get(0).contains("hit-registration.yml"));
    }

    @Test
    void comboReachHandicapEnablesFromItsOwnModuleAndReadsTheTopLevelScale() throws Exception {
        // The 2.4.4 promotion: the reach handicap is its own module with a flat
        // top-level scale (the enable dissolved into the module toggle).
        SnapshotParser.Result result = parse("""
                modules:
                  combo-hold: true
                  combo-reach-handicap: true
                combo-reach-handicap:
                  reach-scale: 0.7
                """, "", "", "");

        Snapshot snapshot = result.snapshot();
        assertTrue(snapshot.enabled(Feature.COMBO_REACH_HANDICAP), "the module toggle flips it on");
        ReachHandicapSettings handicap = settings(snapshot, Feature.COMBO_REACH_HANDICAP);
        assertEquals(0.7, handicap.scale(), "the in-range top-level scale is stored verbatim");
        assertTrue(result.issues().isEmpty(), () -> "unexpected issues: " + result.issues());
    }

    @Test
    void comboReachHandicapDefaultsOffAndToItsScaleDefaultWhenAbsent() throws Exception {
        // No module key and no block: OFF and 0.87 — the era-exact no-op.
        Snapshot snapshot = parse("", "", "", "").snapshot();
        assertFalse(snapshot.enabled(Feature.COMBO_REACH_HANDICAP), "default OFF (era-exact no-op)");
        assertEquals(ReachHandicapSettings.DEFAULTS, settings(snapshot, Feature.COMBO_REACH_HANDICAP));
    }

    @Test
    void comboReachScaleOutOfRangeWarnsOnceAndFallsBack() throws Exception {
        // 1.4 would INFLATE reach — a handicap never does; it warns and the default 0.87 stands.
        SnapshotParser.Result result = parse("""
                modules:
                  combo-hold: true
                  combo-reach-handicap: true
                combo-reach-handicap:
                  reach-scale: 1.4
                """, "", "", "");

        Snapshot snapshot = result.snapshot();
        ReachHandicapSettings handicap = settings(snapshot, Feature.COMBO_REACH_HANDICAP);
        assertEquals(ReachHandicapSettings.DEFAULTS.scale(), handicap.scale(),
                "out-of-range scale fell back to the default");
        assertTrue(snapshot.enabled(Feature.COMBO_REACH_HANDICAP), "the module still enabled it");
        assertEquals(1, result.issues().size(), () -> "issues: " + result.issues());
        assertTrue(result.issues().get(0).contains("reach-scale"));
        assertTrue(result.issues().get(0).contains("combo-reach-handicap"));
    }

    @Test
    void legacyNestedReachHandicapMigratesLoudlyToTheModule() throws Exception {
        // In-place upgrade from 2.4.3-beta: the old nested combo-hold.reach-handicap
        // block with NO new module key. The parser honours it (enabled) and carries its
        // tuned scale, and warns once naming BOTH keys — never silently ignored.
        SnapshotParser.Result result = parse("""
                modules:
                  combo-hold: true
                combo-hold:
                  reach-handicap:
                    enabled: true
                    reach-scale: 0.7
                """, "", "", "");

        Snapshot snapshot = result.snapshot();
        assertTrue(snapshot.enabled(Feature.COMBO_REACH_HANDICAP),
                "the legacy nested enable migrates to the module enabled bit");
        ReachHandicapSettings handicap = settings(snapshot, Feature.COMBO_REACH_HANDICAP);
        assertEquals(0.7, handicap.scale(),
                "the legacy nested reach-scale carries over for one release window");
        assertEquals(1, result.issues().size(), () -> "issues: " + result.issues());
        assertTrue(result.issues().get(0).contains("combo-reach-handicap"), () -> result.issues().get(0));
        assertTrue(result.issues().get(0).contains("combo-hold.reach-handicap"), () -> result.issues().get(0));
    }

    @Test
    void anExplicitReachHandicapModuleKeyWinsOverTheLegacyNestedEnable() throws Exception {
        // Once the operator has set the new module key, the legacy nested enable is
        // ignored (no migration, no warn) — the module key is the single source.
        SnapshotParser.Result result = parse("""
                modules:
                  combo-hold: true
                  combo-reach-handicap: false
                combo-hold:
                  reach-handicap:
                    enabled: true
                """, "", "", "");

        Snapshot snapshot = result.snapshot();
        assertFalse(snapshot.enabled(Feature.COMBO_REACH_HANDICAP),
                "the explicit module key (false) wins over the legacy nested enable");
        assertTrue(result.issues().isEmpty(), () -> "unexpected issues: " + result.issues());
    }

    @Test
    void comboReachHandicapEngagesStandaloneWithoutComboHold() throws Exception {
        // Since the 2.4.5 detection/servo split the handicap drives combo DETECTION
        // itself, so on-without-combo-hold is a fully working feature — no dependency
        // warning is emitted (the two combo modules toggle independently).
        SnapshotParser.Result result = parse("""
                modules:
                  combo-hold: false
                  combo-reach-handicap: true
                """, "", "", "");

        assertTrue(result.snapshot().enabled(Feature.COMBO_REACH_HANDICAP));
        assertFalse(result.snapshot().enabled(Feature.COMBO_HOLD));
        assertTrue(result.issues().isEmpty(), () -> "no dependency warning expected: " + result.issues());
    }

    @Test
    void servoClampWithMinFactorAboveMaxFactorWarnsAndFallsBackToDefaults() throws Exception {
        // A transposed pair (min > max) would pin sigma to min-factor on every combo
        // hit through the clamp's Math.max(min, ...) — a constant amplifier. The parser
        // must warn and fall BOTH back to the defaults, not accept it silently.
        SnapshotParser.Result result = parse("""
                combo-hold:
                  min-factor: 1.2
                  max-factor: 0.8
                """, "", "", "");

        ComboSettings combo = settings(result.snapshot(), Feature.COMBO_HOLD);
        assertEquals(ComboSettings.DEFAULTS.minFactor(), combo.minFactor(), "min-factor fell back");
        assertEquals(ComboSettings.DEFAULTS.maxFactor(), combo.maxFactor(), "max-factor fell back");
        assertEquals(1, result.issues().size(), () -> "issues: " + result.issues());
        assertTrue(result.issues().get(0).contains("min-factor"), () -> result.issues().get(0));
        assertTrue(result.issues().get(0).contains("max-factor"), () -> result.issues().get(0));
    }

    @Test
    void potsSettingsReadFromTheConfig() throws Exception {
        SnapshotParser.Result result = parse("""
                pot-fill:
                  permission: "server.vip.pots"
                  cost-per-potion: 5.0
                fast-pots:
                  angle-degrees: 50.0
                  min-speed-multiplier: 0.4
                  max-speed-multiplier: 2.5
                  lead-ticks: 2.0
                """, "", "", "");
        assertTrue(result.issues().isEmpty(), () -> "unexpected issues: " + result.issues());

        PotFillSettings potFill = settings(result.snapshot(), Feature.POT_FILL);
        assertEquals("server.vip.pots", potFill.permission());
        assertEquals(5.0, potFill.costPerPotion());

        FastPotsSettings fastPots = settings(result.snapshot(), Feature.FAST_POTS);
        assertEquals(50.0, fastPots.angleDegrees());
        assertEquals(0.4, fastPots.minSpeedMultiplier());
        assertEquals(2.5, fastPots.maxSpeedMultiplier());
        assertEquals(2.0, fastPots.leadTicks());
    }

    @Test
    void fastPotsKnobsAreParseClampedToTheirBounds() throws Exception {
        // Angle above 90, ceiling above 5, floor above 1 and lead above 5 each clamp
        // to the nearest bound, one warn apiece.
        SnapshotParser.Result high = parse("""
                fast-pots:
                  angle-degrees: 120.0
                  min-speed-multiplier: 3.0
                  max-speed-multiplier: 9.0
                  lead-ticks: 12.0
                """, "", "", "");
        FastPotsSettings clampedHigh = settings(high.snapshot(), Feature.FAST_POTS);
        assertEquals(FastPotsSettings.MAX_ANGLE, clampedHigh.angleDegrees());
        assertEquals(FastPotsSettings.MAX_MIN_MULTIPLIER, clampedHigh.minSpeedMultiplier());
        assertEquals(FastPotsSettings.MAX_MAX_MULTIPLIER, clampedHigh.maxSpeedMultiplier());
        assertEquals(FastPotsSettings.MAX_LEAD, clampedHigh.leadTicks());
        assertEquals(4, high.issues().size(), () -> "issues: " + high.issues());

        // A ceiling below 1.0, a floor below its positive minimum and a negative lead
        // clamp up to their floors.
        SnapshotParser.Result low = parse("""
                fast-pots:
                  min-speed-multiplier: 0.001
                  max-speed-multiplier: 0.2
                  lead-ticks: -3.0
                """, "", "", "");
        FastPotsSettings clampedLow = settings(low.snapshot(), Feature.FAST_POTS);
        assertEquals(FastPotsSettings.MIN_MIN_MULTIPLIER, clampedLow.minSpeedMultiplier());
        assertEquals(FastPotsSettings.MIN_MAX_MULTIPLIER, clampedLow.maxSpeedMultiplier());
        assertEquals(FastPotsSettings.MIN_LEAD, clampedLow.leadTicks());
        assertEquals(3, low.issues().size(), () -> "issues: " + low.issues());
    }

    @Test
    void hitFeedbackCustomListsReadFromTheConfig() throws Exception {
        // preset: custom reads the sounds:/particles: lists — the config's first
        // list-of-records shape, each entry re-wrapped into its own reader.
        Snapshot snapshot = parse("""
                hit-feedback:
                  preset: custom
                  sounds:
                    - sound: entity.player.hurt
                      volume: 0.9
                      pitch: 1.2
                    - sound: block.anvil.land
                      volume: 0.5
                      pitch: 0.6
                  particles:
                    - particle: crit
                      count-min: 3
                      count-max: 5
                      mode: spread
                      spread: {x: 0.2, y: 0.3, z: 0.2}
                  low-health-threshold-hearts: 5.0
                  low-health-sounds:
                    - sound: entity.glow_squid.hurt
                      volume: 0.9
                      pitch: 1.2
                """, "", "", "").snapshot();
        HitFeedbackSettings s = settings(snapshot, Feature.HIT_FEEDBACK);
        assertEquals(HitFeedbackSettings.Preset.CUSTOM, s.preset());
        assertEquals(2, s.sounds().size());
        assertEquals("entity.player.hurt", s.sounds().get(0).sound());
        assertEquals(0.9f, s.sounds().get(0).volume(), 1e-6);
        assertEquals(1.2f, s.sounds().get(0).pitch(), 1e-6);
        assertEquals(1, s.particles().size());
        assertEquals(HitFeedbackSettings.Mode.SPREAD, s.particles().get(0).mode());
        assertEquals(0.3, s.particles().get(0).spreadY(), 1e-9);
        // The low-health extra layer reads its own list and threshold under custom.
        assertEquals(5.0, s.lowHealthThresholdHearts(), 1e-9);
        assertEquals(1, s.lowHealthSounds().size());
        assertEquals("entity.glow_squid.hurt", s.lowHealthSounds().get(0).sound());
        assertEquals(0.9f, s.lowHealthSounds().get(0).volume(), 1e-6);
        assertEquals(1.2f, s.lowHealthSounds().get(0).pitch(), 1e-6);
    }

    @Test
    void hitFeedbackPresetsResolveTheirInCodeLists() throws Exception {
        // A non-custom preset ignores the lists and resolves the in-code constants.
        Snapshot snapshot = parse("""
                hit-feedback:
                  preset: signature
                """, "", "", "").snapshot();
        HitFeedbackSettings s = settings(snapshot, Feature.HIT_FEEDBACK);
        assertEquals(HitFeedbackSettings.SIGNATURE_SOUNDS, s.sounds());
        assertEquals(HitFeedbackSettings.SIGNATURE_PARTICLES, s.particles());
        // The signature preset also carries its own low-health extra sound layer.
        assertEquals(HitFeedbackSettings.SIGNATURE_LOW_HEALTH_SOUNDS, s.lowHealthSounds());
    }

    @Test
    void hitFeedbackKnobsAreParseClampedToTheirBounds() throws Exception {
        // A volume above the ceiling and a pitch below the floor each clamp to the
        // nearest bound (per-entry numberClamped, one warn apiece).
        Snapshot snapshot = parse("""
                hit-feedback:
                  preset: custom
                  sounds:
                    - sound: entity.player.hurt
                      volume: 99
                      pitch: 0.01
                """, "", "", "").snapshot();
        HitFeedbackSettings s = settings(snapshot, Feature.HIT_FEEDBACK);
        assertEquals(HitFeedbackSettings.MAX_VOLUME, s.sounds().get(0).volume(), 1e-6);
        assertEquals(HitFeedbackSettings.MIN_PITCH, s.sounds().get(0).pitch(), 1e-6);
    }

    @Test
    void damageIndicatorKnobsReadAndClamp() throws Exception {
        // lifetime-ticks above MAX_LIFETIME clamps down; the in-range knobs read
        // verbatim, an absent crit-text keeps its default.
        Snapshot snapshot = parse("""
                damage-indicators:
                  lifetime-ticks: 500
                  ring-radius: 0.8
                  text: "&e{HEALTH}"
                  crit-threshold-hearts: 3.5
                """, "", "", "").snapshot();
        DamageIndicatorsSettings s = settings(snapshot, Feature.DAMAGE_INDICATORS);
        assertEquals(DamageIndicatorsSettings.MAX_LIFETIME, s.lifetimeTicks());
        assertEquals(0.8, s.ringRadius(), 1e-9);
        assertEquals("&e{HEALTH}", s.text());
        assertEquals(3.5, s.critThresholdHearts(), 1e-9);
        assertEquals(DamageIndicatorsSettings.DEFAULTS.critText(), s.critText());
    }

    @Test
    void damageIndicatorLifetimeClampsHighWithAWarning() throws Exception {
        // intClamped is the integer twin of numberClamped: a high value is pulled to
        // MAX_LIFETIME with exactly one warn (intAtLeast would have silently kept it).
        SnapshotParser.Result result = parse("""
                damage-indicators:
                  lifetime-ticks: 500
                """, "", "", "");
        DamageIndicatorsSettings s = settings(result.snapshot(), Feature.DAMAGE_INDICATORS);
        assertEquals(DamageIndicatorsSettings.MAX_LIFETIME, s.lifetimeTicks());
        assertEquals(1, result.issues().size(), () -> "issues: " + result.issues());
        assertTrue(result.issues().get(0).contains("lifetime-ticks"), () -> result.issues().get(0));
    }

    @Test
    void deathEffectsCustomKnobsReadFromTheConfig() throws Exception {
        // preset: custom reads the lightning flag and the sounds:/particles:
        // lists — the same list-of-records shape hit-feedback introduced. A
        // dust particle carries its RRGGBB hex color in the block field (the
        // runtime maps particle "dust" that way; there is no block state).
        Snapshot snapshot = parse("""
                death-effects:
                  preset: custom
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
                """, "", "", "").snapshot();
        DeathEffectsSettings s = settings(snapshot, Feature.DEATH_EFFECTS);
        assertEquals(DeathEffectsSettings.Preset.CUSTOM, s.preset());
        assertTrue(s.lightning(), "custom preset honours the lightning flag");
        assertEquals(1, s.sounds().size());
        assertEquals("entity.lightning_bolt.thunder", s.sounds().get(0).sound());
        assertEquals(0.8f, s.sounds().get(0).volume(), 1e-6);
        assertEquals(1.4f, s.sounds().get(0).pitch(), 1e-6);
        assertEquals(1, s.particles().size());
        assertEquals("dust", s.particles().get(0).particle());
        assertEquals("ff00aa", s.particles().get(0).block(),
                "the dust hex color rides the block field");
        assertEquals(HitFeedbackSettings.Mode.SPREAD, s.particles().get(0).mode());
        assertEquals(0.5, s.particles().get(0).spreadY(), 1e-9);
    }

    @Test
    void deathEffectsPresetsDispatchTheirInCodeSets() throws Exception {
        // A named preset resolves the in-code constants and ignores the custom
        // knobs entirely — signature strikes its cosmetic lightning even with
        // the custom flag written false alongside it.
        Snapshot snapshot = parse("""
                death-effects:
                  preset: signature
                  lightning: false
                """, "", "", "").snapshot();
        DeathEffectsSettings s = settings(snapshot, Feature.DEATH_EFFECTS);
        assertTrue(s.lightning(), "signature strikes lightning regardless of the custom flag");
        assertEquals(DeathEffectsSettings.SIGNATURE_SOUNDS, s.sounds());
        assertEquals(DeathEffectsSettings.SIGNATURE_PARTICLES, s.particles());
        // The owner's tune, pinned by value: the glow-squid death call over a
        // white/yellow/gold dust burst plus uncolored firework sparks.
        assertEquals("entity.glow_squid.death", s.sounds().get(0).sound());
        assertEquals(4, s.particles().size());
        assertEquals("ffffff", s.particles().get(0).block());
        assertEquals("ffff55", s.particles().get(1).block());
        assertEquals("ffaa00", s.particles().get(2).block());
        assertEquals("firework", s.particles().get(3).particle());
    }

    @Test
    void probeStrategyIsStoredRawSoTheParserStaysVersionBlind() throws Exception {
        // The parser no longer resolves the transport — that is version-aware and happens
        // at the boot seam (ProbeStrategy.resolveEffective, pinned in ProbeStrategyTest).
        // Here KEEPALIVE is a valid enum value stored verbatim, with no parse-time issue.
        SnapshotParser.Result result = parse("", "", "", """
                latency-compensation:
                  probe-strategy: KEEPALIVE
                """);
        CompensationSettings comp = settings(result.snapshot(), Feature.LATENCY_COMPENSATION);
        assertEquals(ProbeStrategy.KEEPALIVE, comp.probeStrategy(), "the raw configured value is stored");
        assertTrue(result.issues().isEmpty(), () -> "the parser must stay version-blind: " + result.issues());
    }

    @Test
    void retiredPingOffsetKeyReportsAndStillParses() throws Exception {
        // ping-offset-ms was retired in 2.4.9 (never applied). A lingering key earns a
        // one-line notice; the surviving spike/off-ground-sync knobs still parse.
        SnapshotParser.Result result = parse("", "", "", """
                latency-compensation:
                  ping-offset-ms: 25
                  spike-threshold-ms: 60
                  off-ground-sync: false
                """);
        assertEquals(1, result.issues().size(), () -> "issues: " + result.issues());
        assertTrue(result.issues().get(0).contains("ping-offset-ms"), () -> result.issues().get(0));
        assertTrue(result.issues().get(0).contains("retired"), () -> result.issues().get(0));

        CompensationSettings comp = settings(result.snapshot(), Feature.LATENCY_COMPENSATION);
        assertEquals(60, comp.spikeThresholdMillis());
        assertFalse(comp.offGroundSync());
    }

    @Test
    void transactionProbeStrategyParsesAsAValidValue() throws Exception {
        SnapshotParser.Result result = parse("", "", "", """
                latency-compensation:
                  probe-strategy: TRANSACTION
                """);
        assertTrue(result.issues().isEmpty(), () -> "unexpected issues: " + result.issues());
        CompensationSettings comp = settings(result.snapshot(), Feature.LATENCY_COMPENSATION);
        assertEquals(ProbeStrategy.TRANSACTION, comp.probeStrategy());
    }

    @Test
    void pingProbeStrategyParsesWithoutAnIssue() throws Exception {
        SnapshotParser.Result result = parse("", "", "", """
                latency-compensation:
                  probe-strategy: PING
                """);
        assertTrue(result.issues().isEmpty(), () -> "unexpected issues: " + result.issues());
        CompensationSettings comp = settings(result.snapshot(), Feature.LATENCY_COMPENSATION);
        assertEquals(ProbeStrategy.PING, comp.probeStrategy());
    }

    @Test
    void snapshotIsImmutableAndFreshPerParse() throws Exception {
        SnapshotParser.Result first = parse("", "", "", "");
        SnapshotParser.Result second = parse("", "", "", "");
        assertNotSame(first.snapshot(), second.snapshot(), "each parse is a fresh instance");

        // The issue list is an immutable copy.
        assertThrows(UnsupportedOperationException.class, () -> first.issues().add("x"));

        // Mutating the source after parse cannot change the snapshot.
        YamlConfiguration main = yaml("modules:\n  knockback: true\n");
        Configuration knockback = yaml("");
        Snapshot snapshot = SnapshotParser.parse(main, knockback, yaml(""), yaml(""), Map.of()).snapshot();
        main.set("modules.knockback", false);
        assertTrue(snapshot.enabled(Feature.KNOCKBACK), "snapshot captured the value at parse time");
    }
}
