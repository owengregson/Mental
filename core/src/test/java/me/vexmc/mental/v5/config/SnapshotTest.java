package me.vexmc.mental.v5.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.v5.config.settings.ComboSettings;
import me.vexmc.mental.v5.config.settings.ChargedAttackSettings;
import me.vexmc.mental.v5.config.settings.CompensationSettings;
import me.vexmc.mental.v5.config.settings.CraftingSettings;
import me.vexmc.mental.v5.config.settings.DropProtectionSettings;
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
import me.vexmc.mental.v5.config.settings.WeaponSpeedSettings;
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

    /** A full source set from file-name → YAML text; absent files parse empty. */
    private static ConfigStore.Sources sources(Map<String, String> files) throws Exception {
        return new ConfigStore.Sources(
                yaml(files.getOrDefault(ConfigStore.MAIN_FILE, "")),
                yaml(files.getOrDefault(ConfigStore.KNOCKBACK_FILE, "")),
                yaml(files.getOrDefault(ConfigStore.HIT_REG_FILE, "")),
                yaml(files.getOrDefault(ConfigStore.LATENCY_FILE, "")),
                yaml(files.getOrDefault(ConfigStore.COMBO_FILE, "")),
                yaml(files.getOrDefault(ConfigStore.POTS_FILE, "")),
                yaml(files.getOrDefault(ConfigStore.LOADOUT_FILE, "")),
                yaml(files.getOrDefault(ConfigStore.DROP_PROTECTION_FILE, "")),
                yaml(files.getOrDefault(ConfigStore.EFFECTS_FILE, "")),
                Map.of(),
                Map.of());
    }

    private static SnapshotParser.Result parseFiles(Map<String, String> files) throws Exception {
        return SnapshotParser.parse(sources(files));
    }

    private static SnapshotParser.Result parse(
            String main, String knockback, String hitReg, String compensation) throws Exception {
        return parseFiles(Map.of(
                ConfigStore.MAIN_FILE, main,
                ConfigStore.KNOCKBACK_FILE, knockback,
                ConfigStore.HIT_REG_FILE, hitReg,
                ConfigStore.LATENCY_FILE, compensation));
    }

    /** A real bundled resource, exactly as the plugin jar serves it. */
    private static String bundled(String name) throws Exception {
        try (var stream = SnapshotTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(stream, () -> "missing bundled resource: " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
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
        // The FEEDBACK family reads the selected Combat Effects preset; with no
        // effects.yml and no preset files the selection is signature and the
        // in-code FALLBACK stands in — the settings DEFAULTS (the era-exact
        // no-op) exactly.
        assertEquals("signature", snapshot.selectedEffectsPreset());
        assertEquals(HitFeedbackSettings.DEFAULTS, settings(snapshot, Feature.HIT_FEEDBACK));
        // The low-health extra layer defaults to no layer / a 35%-of-max ceiling.
        HitFeedbackSettings feedback = settings(snapshot, Feature.HIT_FEEDBACK);
        assertTrue(feedback.lowHealthSounds().isEmpty(), "no low-health layer by default (vanilla feel)");
        assertEquals(35.0, feedback.lowHealthThresholdPercent(), 0.0,
                "default low-health threshold is 35% of max health");
        assertEquals(DamageIndicatorsSettings.DEFAULTS, settings(snapshot, Feature.DAMAGE_INDICATORS));
        assertEquals(DeathEffectsSettings.DEFAULTS, settings(snapshot, Feature.DEATH_EFFECTS));
        // The vanilla death tune is a strict nothing — no lightning, no sounds,
        // no particles (the module toggle owns zero-touch; enabled-but-vanilla is
        // a no-op by construction).
        DeathEffectsSettings death = settings(snapshot, Feature.DEATH_EFFECTS);
        assertFalse(death.lightning(), "vanilla death tune strikes no lightning");
        assertTrue(death.sounds().isEmpty(), "vanilla death tune plays nothing");
        assertTrue(death.particles().isEmpty(), "vanilla death tune pops nothing");
        assertTrue(death.fireworkColors().isEmpty(), "vanilla death tune launches no firework");
        // Drop protection defaults to the shipped 15-second gold-glow window
        // (inert while the module is OFF, which is the default).
        assertEquals(DropProtectionSettings.DEFAULTS, settings(snapshot, Feature.DROP_PROTECTION));
        // The two Combat Test 8c settings-carrying features parse-empty to their
        // code-confirmed DEFAULTS (spec §2.1/§2.2) — the era-exact no-op, inert
        // while the modules are OFF (the default).
        assertEquals(WeaponSpeedSettings.DEFAULTS, settings(snapshot, Feature.WEAPON_ATTACK_SPEEDS));
        assertEquals(ChargedAttackSettings.DEFAULTS, settings(snapshot, Feature.CHARGED_ATTACKS));
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
    void dropProtectionParsesSecondsAndGlowColor() throws Exception {
        SnapshotParser.Result result = parseFiles(Map.of(ConfigStore.DROP_PROTECTION_FILE, """
                drop-protection:
                  seconds: 30
                  glow-color: YELLOW
                """));
        assertTrue(result.issues().isEmpty(), () -> "issues: " + result.issues());
        DropProtectionSettings dp = settings(result.snapshot(), Feature.DROP_PROTECTION);
        assertEquals(30, dp.seconds());
        assertEquals(DropProtectionSettings.GlowColor.YELLOW, dp.glowColor());
    }

    @Test
    void dropProtectionClampsSecondsAndFallsBackOnUnknownColor() throws Exception {
        SnapshotParser.Result result = parseFiles(Map.of(ConfigStore.DROP_PROTECTION_FILE, """
                drop-protection:
                  seconds: 0
                  glow-color: rainbow
                """));
        DropProtectionSettings dp = settings(result.snapshot(), Feature.DROP_PROTECTION);
        assertEquals(1, dp.seconds(), "seconds clamps to a floor of 1");
        assertEquals(DropProtectionSettings.GlowColor.GOLD, dp.glowColor(),
                "an unknown glow colour falls back to the GOLD default");
        assertEquals(2, result.issues().size(), () -> "issues: " + result.issues());
    }

    @Test
    void ct8cWeaponSpeedsAndChargedAttacksParseFromTheConfig() throws Exception {
        // The two settings-carrying CT8c features read top-level config.yml
        // sections; a set key overrides its default, an unset key keeps it.
        SnapshotParser.Result result = parse("""
                weapon-attack-speeds:
                  attacks-per-second:
                    sword: 3.5
                    hoe:
                      netherite: 4.0
                charged-attacks:
                  require-full-charge: false
                  miss-recovery-ticks: 6
                  charged-threshold: 1.8
                  charged-reach-bonus: 0.5
                  deny-bonus-while-crouching: false
                """, "", "", "");
        assertTrue(result.issues().isEmpty(), () -> "issues: " + result.issues());

        WeaponSpeedSettings speeds = settings(result.snapshot(), Feature.WEAPON_ATTACK_SPEEDS);
        assertEquals(3.5, speeds.sword(), "the set class speed overrides the default");
        assertEquals(2.5, speeds.fist(), "an untouched class keeps its default");
        assertEquals(4.0, speeds.hoe().netherite(), "the set hoe tier overrides its default");
        assertEquals(3.0, speeds.hoe().iron(), "an untouched hoe tier keeps its default");

        ChargedAttackSettings charged = settings(result.snapshot(), Feature.CHARGED_ATTACKS);
        assertFalse(charged.requireFullCharge());
        assertEquals(6, charged.missRecoveryTicks());
        assertEquals(1.8, charged.chargedThreshold());
        assertEquals(0.5, charged.chargedReachBonus());
        assertFalse(charged.denyBonusWhileCrouching());
    }

    @Test
    void ct8cSettingsFeaturesResolveToTheirTypedRecordsNeverNoSettings() throws Exception {
        // The settingsFor-default→NoSettings mistype guard: the two settings-carrying
        // CT8c features must resolve to their real record TYPES — a NoSettings under a
        // SettingsKey<WeaponSpeedSettings> would be a silent type mismatch. The eleven
        // toggle-only CT8c features correctly share the NoSettings singleton.
        Snapshot snapshot = parse("", "", "", "").snapshot();
        assertInstanceOf(WeaponSpeedSettings.class,
                settings(snapshot, Feature.WEAPON_ATTACK_SPEEDS));
        assertInstanceOf(ChargedAttackSettings.class,
                settings(snapshot, Feature.CHARGED_ATTACKS));
        for (Feature toggleOnly : List.of(Feature.CT8C_DAMAGE, Feature.CT8C_CRITS, Feature.CT8C_SWEEP,
                Feature.CT8C_IFRAMES, Feature.CT8C_SHIELDS, Feature.CT8C_REGEN,
                Feature.CT8C_CONSUMABLES, Feature.CT8C_POTIONS, Feature.CT8C_REACH,
                Feature.CT8C_PROJECTILES, Feature.CLEAVING)) {
            assertSame(NoSettings.DEFAULTS, settings(snapshot, toggleOnly),
                    () -> toggleOnly + " is toggle-only and must carry NoSettings.DEFAULTS");
        }
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
        // top-level scale (the enable dissolved into the module toggle). Since
        // 2.5.2 the scale block lives in combo.yml; the toggle stays in config.yml.
        SnapshotParser.Result result = parseFiles(Map.of(
                ConfigStore.MAIN_FILE, """
                        modules:
                          combo-hold: true
                          combo-reach-handicap: true
                        """,
                ConfigStore.COMBO_FILE, """
                        combo-reach-handicap:
                          reach-scale: 0.7
                        """));

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
        SnapshotParser.Result result = parseFiles(Map.of(
                ConfigStore.MAIN_FILE, """
                        modules:
                          combo-hold: true
                          combo-reach-handicap: true
                        """,
                ConfigStore.COMBO_FILE, """
                        combo-reach-handicap:
                          reach-scale: 1.4
                        """));

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
        // In-place upgrade from 2.4.3-beta whose combo-hold block moved wholesale into
        // combo.yml: the old nested combo-hold.reach-handicap block with NO new module
        // key. The parser honours it (enabled) and carries its tuned scale, and warns
        // once naming BOTH keys — never silently ignored. (The straight-2.4.3 shape,
        // still in config.yml, is pinned separately by the moved-section tests.)
        SnapshotParser.Result result = parseFiles(Map.of(
                ConfigStore.MAIN_FILE, """
                        modules:
                          combo-hold: true
                        """,
                ConfigStore.COMBO_FILE, """
                        combo-hold:
                          reach-handicap:
                            enabled: true
                            reach-scale: 0.7
                        """));

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
        SnapshotParser.Result result = parseFiles(Map.of(
                ConfigStore.MAIN_FILE, """
                        modules:
                          combo-hold: true
                          combo-reach-handicap: false
                        """,
                ConfigStore.COMBO_FILE, """
                        combo-hold:
                          reach-handicap:
                            enabled: true
                        """));

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
        SnapshotParser.Result result = parseFiles(Map.of(
                ConfigStore.COMBO_FILE, """
                        combo-hold:
                          min-factor: 1.2
                          max-factor: 0.8
                        """));

        ComboSettings combo = settings(result.snapshot(), Feature.COMBO_HOLD);
        assertEquals(ComboSettings.DEFAULTS.minFactor(), combo.minFactor(), "min-factor fell back");
        assertEquals(ComboSettings.DEFAULTS.maxFactor(), combo.maxFactor(), "max-factor fell back");
        assertEquals(1, result.issues().size(), () -> "issues: " + result.issues());
        assertTrue(result.issues().get(0).contains("min-factor"), () -> result.issues().get(0));
        assertTrue(result.issues().get(0).contains("max-factor"), () -> result.issues().get(0));
    }

    @Test
    void potsSettingsReadFromTheConfig() throws Exception {
        SnapshotParser.Result result = parseFiles(Map.of(
                ConfigStore.POTS_FILE, """
                        pot-fill:
                          permission: "server.vip.pots"
                          cost-per-potion: 5.0
                        fast-pots:
                          angle-degrees: 50.0
                          min-speed-multiplier: 0.4
                          max-speed-multiplier: 2.5
                          lead-ticks: 2.0
                        """));
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
        SnapshotParser.Result high = parseFiles(Map.of(
                ConfigStore.POTS_FILE, """
                        fast-pots:
                          angle-degrees: 120.0
                          min-speed-multiplier: 3.0
                          max-speed-multiplier: 9.0
                          lead-ticks: 12.0
                        """));
        FastPotsSettings clampedHigh = settings(high.snapshot(), Feature.FAST_POTS);
        assertEquals(FastPotsSettings.MAX_ANGLE, clampedHigh.angleDegrees());
        assertEquals(FastPotsSettings.MAX_MIN_MULTIPLIER, clampedHigh.minSpeedMultiplier());
        assertEquals(FastPotsSettings.MAX_MAX_MULTIPLIER, clampedHigh.maxSpeedMultiplier());
        assertEquals(FastPotsSettings.MAX_LEAD, clampedHigh.leadTicks());
        assertEquals(4, high.issues().size(), () -> "issues: " + high.issues());

        // A ceiling below 1.0, a floor below its positive minimum and a negative lead
        // clamp up to their floors.
        SnapshotParser.Result low = parseFiles(Map.of(
                ConfigStore.POTS_FILE, """
                        fast-pots:
                          min-speed-multiplier: 0.001
                          max-speed-multiplier: 0.2
                          lead-ticks: -3.0
                        """));
        FastPotsSettings clampedLow = settings(low.snapshot(), Feature.FAST_POTS);
        assertEquals(FastPotsSettings.MIN_MIN_MULTIPLIER, clampedLow.minSpeedMultiplier());
        assertEquals(FastPotsSettings.MIN_MAX_MULTIPLIER, clampedLow.maxSpeedMultiplier());
        assertEquals(FastPotsSettings.MIN_LEAD, clampedLow.leadTicks());
        assertEquals(3, low.issues().size(), () -> "issues: " + low.issues());
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
        Snapshot snapshot = SnapshotParser.parse(
                ConfigStore.Sources.of(main, knockback, yaml(""), yaml(""), Map.of())).snapshot();
        main.set("modules.knockback", false);
        assertTrue(snapshot.enabled(Feature.KNOCKBACK), "snapshot captured the value at parse time");
    }

    /* ------------------------------------------------------------------ */
    /*  The 2.5.2 per-concern split — routing, parity, and back-compat     */
    /* ------------------------------------------------------------------ */

    @Test
    void bundledSplitFilesParseCleanAndKeepEveryEffectiveDefault() throws Exception {
        // The fresh-install pin: every 2.5.2 split file, exactly as the jar ships
        // it, parses with ZERO issues, and the EFFECTIVE settings are the same
        // era-exact no-ops parse(empty) yields.
        SnapshotParser.Result result = parseFiles(Map.of(
                ConfigStore.COMBO_FILE, bundled(ConfigStore.COMBO_FILE),
                ConfigStore.POTS_FILE, bundled(ConfigStore.POTS_FILE),
                ConfigStore.LOADOUT_FILE, bundled(ConfigStore.LOADOUT_FILE)));
        assertTrue(result.issues().isEmpty(), () -> "unexpected issues: " + result.issues());
        Snapshot snapshot = result.snapshot();

        // The commented templates parse to their exact DEFAULTS records.
        assertEquals(ComboSettings.DEFAULTS, settings(snapshot, Feature.COMBO_HOLD));
        assertEquals(ReachHandicapSettings.DEFAULTS, settings(snapshot, Feature.COMBO_REACH_HANDICAP));
        assertEquals(PotFillSettings.DEFAULTS, settings(snapshot, Feature.POT_FILL));
        assertEquals(FastPotsSettings.DEFAULTS, settings(snapshot, Feature.FAST_POTS));
        assertEquals(OffhandSettings.DEFAULTS, settings(snapshot, Feature.OFFHAND));
        assertEquals(CraftingSettings.DEFAULTS, settings(snapshot, Feature.CRAFTING));
    }

    @Test
    void theBundledEffectsSelectionParsesCleanAtTheSignatureDefault() throws Exception {
        // The fresh-install pin for the Combat Effects preset library: the bundled
        // effects.yml selects signature with zero issues, and with no preset files
        // loaded here the in-code FALLBACK stands in — the parse-empty era-exact
        // no-ops (the modules default OFF, so this is what a fresh server carries).
        SnapshotParser.Result result = parseFiles(Map.of(
                ConfigStore.EFFECTS_FILE, bundled(ConfigStore.EFFECTS_FILE)));
        assertTrue(result.issues().isEmpty(), () -> "unexpected issues: " + result.issues());
        Snapshot snapshot = result.snapshot();
        assertEquals("signature", snapshot.selectedEffectsPreset());
        assertEquals(HitFeedbackSettings.DEFAULTS, settings(snapshot, Feature.HIT_FEEDBACK));
        assertEquals(DamageIndicatorsSettings.DEFAULTS, settings(snapshot, Feature.DAMAGE_INDICATORS));
        assertEquals(DeathEffectsSettings.DEFAULTS, settings(snapshot, Feature.DEATH_EFFECTS));
    }

    @Test
    void eachBundledSplitFileParsesToTheSameSettingsFromTheOldConfigYmlLocation() throws Exception {
        // The parity pin: a split file's content IS the old config.yml section
        // shape (the move changed only the routing), so the SAME bundled text
        // loaded as config.yml must parse every moved feature to the SAME
        // settings — plus the loud moved-section notice per honoured section.
        for (String file : ConfigStore.SPLIT_FILE_SECTIONS.keySet()) {
            String body = bundled(file);
            SnapshotParser.Result fromSplit = parseFiles(Map.of(file, body));
            SnapshotParser.Result fromMain = parseFiles(Map.of(ConfigStore.MAIN_FILE, body));
            for (Feature feature : List.of(Feature.COMBO_HOLD, Feature.COMBO_REACH_HANDICAP,
                    Feature.POT_FILL, Feature.FAST_POTS, Feature.OFFHAND, Feature.CRAFTING)) {
                assertEquals((Object) settings(fromSplit.snapshot(), feature),
                        settings(fromMain.snapshot(), feature),
                        () -> file + " parity for " + feature);
            }
            assertTrue(fromSplit.issues().isEmpty(),
                    () -> file + " issues: " + fromSplit.issues());
            // One moved-section line per LIVE section the old location carries
            // (the combo/pots/loadout bundles are commented templates → none).
            long liveSections = ConfigStore.SPLIT_FILE_SECTIONS.get(file).stream()
                    .filter(section -> {
                        try {
                            return yaml(body).getConfigurationSection(section) != null;
                        } catch (Exception impossible) {
                            throw new IllegalStateException(impossible);
                        }
                    })
                    .count();
            assertEquals(liveSections, fromMain.issues().size(),
                    () -> file + " old-location issues: " + fromMain.issues());
            for (String issue : fromMain.issues()) {
                assertTrue(issue.contains("moved to " + file), () -> issue);
            }
        }
    }

    @Test
    void aTunedOldLocationSectionIsHonouredWithOneLoudLine() throws Exception {
        // Back-compat: an upgraded install whose config.yml still carries a tuned
        // moved section keeps its behavior — the values apply verbatim and exactly
        // one issue line names the section, both files, and the way out.
        SnapshotParser.Result result = parseFiles(Map.of(
                ConfigStore.MAIN_FILE, """
                        pot-fill:
                          permission: "server.vip.pots"
                          cost-per-potion: 5.0
                        """));
        PotFillSettings potFill = settings(result.snapshot(), Feature.POT_FILL);
        assertEquals("server.vip.pots", potFill.permission(),
                "the old-location tune applies verbatim");
        assertEquals(5.0, potFill.costPerPotion(), 0.0);
        assertEquals(1, result.issues().size(), () -> "issues: " + result.issues());
        assertTrue(result.issues().get(0).contains("pot-fill"), () -> result.issues().get(0));
        assertTrue(result.issues().get(0).contains("moved to " + ConfigStore.POTS_FILE),
                () -> result.issues().get(0));
        assertTrue(result.issues().get(0).contains("honoured"), () -> result.issues().get(0));
    }

    @Test
    void aTunedOldLocationComboSectionKeepsItsNestedLegacyMigrationToo() throws Exception {
        // The straight 2.4.3 → 2.5.2 upgrade: combo-hold still in config.yml WITH
        // the pre-2.4.4 nested reach-handicap block. Both migrations stack — the
        // section is honoured from the old location (one moved line) AND the nested
        // enable still promotes the module (one promotion line), carrying the scale.
        SnapshotParser.Result result = parseFiles(Map.of(
                ConfigStore.MAIN_FILE, """
                        modules:
                          combo-hold: true
                        combo-hold:
                          gain: 0.9
                          reach-handicap:
                            enabled: true
                            reach-scale: 0.7
                        """));
        Snapshot snapshot = result.snapshot();
        ComboSettings combo = settings(snapshot, Feature.COMBO_HOLD);
        assertEquals(0.9, combo.gain(), 0.0, "the old-location combo tune applies verbatim");
        assertTrue(snapshot.enabled(Feature.COMBO_REACH_HANDICAP),
                "the nested legacy enable still promotes the module");
        ReachHandicapSettings handicap = settings(snapshot, Feature.COMBO_REACH_HANDICAP);
        assertEquals(0.7, handicap.scale(), "the nested legacy scale still carries");
        assertEquals(2, result.issues().size(), () -> "issues: " + result.issues());
        assertTrue(result.issues().stream().anyMatch(issue ->
                        issue.contains("moved to " + ConfigStore.COMBO_FILE)),
                () -> "expected the moved-section line: " + result.issues());
        assertTrue(result.issues().stream().anyMatch(issue ->
                        issue.contains("combo-hold.reach-handicap.enabled")),
                () -> "expected the 2.4.4 promotion line: " + result.issues());
    }

    @Test
    void whenBothLocationsCarryASectionTheSplitFileWinsLoudly() throws Exception {
        // The shadow case: the split file exists AND config.yml still carries the
        // section. The split file wins (it is the documented home) and the shadowed
        // config.yml section is named loudly — never dropped in silence.
        SnapshotParser.Result result = parseFiles(Map.of(
                ConfigStore.MAIN_FILE, """
                        pot-fill:
                          cost-per-potion: 9.0
                        """,
                ConfigStore.POTS_FILE, """
                        pot-fill:
                          cost-per-potion: 5.0
                        """));
        PotFillSettings potFill = settings(result.snapshot(), Feature.POT_FILL);
        assertEquals(5.0, potFill.costPerPotion(), 1e-9, "the split file wins");
        assertEquals(1, result.issues().size(), () -> "issues: " + result.issues());
        assertTrue(result.issues().get(0).contains("ignored"), () -> result.issues().get(0));
        assertTrue(result.issues().get(0).contains(ConfigStore.POTS_FILE),
                () -> result.issues().get(0));
    }
}
