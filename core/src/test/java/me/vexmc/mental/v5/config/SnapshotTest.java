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
import me.vexmc.mental.v5.config.settings.FastPotsSettings;
import me.vexmc.mental.v5.config.settings.FishingKnockbackSettings;
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
        assertEquals(OcmCoordination.AUTO, snapshot.ocmCoordination());
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
        assertTrue(snapshot.enabled(Feature.OCM_COMPAT));
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
                  speed-multiplier: 2.5
                """, "", "", "");
        assertTrue(result.issues().isEmpty(), () -> "unexpected issues: " + result.issues());

        PotFillSettings potFill = settings(result.snapshot(), Feature.POT_FILL);
        assertEquals("server.vip.pots", potFill.permission());
        assertEquals(5.0, potFill.costPerPotion());

        FastPotsSettings fastPots = settings(result.snapshot(), Feature.FAST_POTS);
        assertEquals(50.0, fastPots.angleDegrees());
        assertEquals(2.5, fastPots.speedMultiplier());
    }

    @Test
    void fastPotsKnobsAreParseClampedToTheirBounds() throws Exception {
        // Angle above 90 and multiplier above 5 clamp to the nearest bound, each with one warn.
        SnapshotParser.Result high = parse("""
                fast-pots:
                  angle-degrees: 120.0
                  speed-multiplier: 9.0
                """, "", "", "");
        FastPotsSettings clampedHigh = settings(high.snapshot(), Feature.FAST_POTS);
        assertEquals(FastPotsSettings.MAX_ANGLE, clampedHigh.angleDegrees());
        assertEquals(FastPotsSettings.MAX_MULTIPLIER, clampedHigh.speedMultiplier());
        assertEquals(2, high.issues().size(), () -> "issues: " + high.issues());

        // A multiplier below 1.0 clamps up to the floor (never slower than vanilla).
        SnapshotParser.Result low = parse("""
                fast-pots:
                  speed-multiplier: 0.2
                """, "", "", "");
        FastPotsSettings clampedLow = settings(low.snapshot(), Feature.FAST_POTS);
        assertEquals(FastPotsSettings.MIN_MULTIPLIER, clampedLow.speedMultiplier());
        assertEquals(1, low.issues().size(), () -> "issues: " + low.issues());
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
