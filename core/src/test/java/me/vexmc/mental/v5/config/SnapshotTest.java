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
import me.vexmc.mental.v5.config.settings.FishingKnockbackSettings;
import me.vexmc.mental.v5.config.settings.HitRegSettings;
import me.vexmc.mental.v5.config.settings.NoSettings;
import me.vexmc.mental.v5.config.settings.OffhandSettings;
import me.vexmc.mental.v5.config.settings.ProjectileKnockbackSettings;
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
        assertEquals(ComboSettings.DEFAULTS, settings(snapshot, Feature.COMBO_HOLD));
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
