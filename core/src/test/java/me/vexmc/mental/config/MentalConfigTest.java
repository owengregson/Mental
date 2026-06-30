package me.vexmc.mental.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import me.vexmc.mental.common.debug.DebugCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class MentalConfigTest {

    /* ------------------------------------------------------------------ */
    /*  Source builders                                                    */
    /* ------------------------------------------------------------------ */

    private static YamlConfiguration yaml(String content) throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(content);
        return configuration;
    }

    private static ConfigSources sources(
            String main, String knockback, String hitReg, String latency,
            Map<String, ConfigurationSection> profiles) throws Exception {
        return new ConfigSources(yaml(main), yaml(knockback), yaml(hitReg), yaml(latency), profiles);
    }

    private static ConfigSources empty() throws Exception {
        return sources("", "", "", "", Map.of());
    }

    private static YamlConfiguration resource(String name) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                MentalConfigTest.class.getClassLoader().getResourceAsStream(name),
                StandardCharsets.UTF_8));
    }

    /** The bundled files, exactly as ConfigStore would extract and load them. */
    private static ConfigSources bundled() {
        Map<String, ConfigurationSection> profiles = new TreeMap<>();
        for (String preset : ConfigStore.BUNDLED_PROFILES) {
            profiles.put(preset, resource("profiles/" + preset + ".yml"));
        }
        return new ConfigSources(
                resource("config.yml"),
                resource("knockback.yml"),
                resource("hit-registration.yml"),
                resource("latency-compensation.yml"),
                profiles);
    }

    /* ------------------------------------------------------------------ */
    /*  Defaults                                                           */
    /* ------------------------------------------------------------------ */

    @Test
    void emptySourcesYieldAllDefaultsWithoutWarnings() throws Exception {
        MentalConfig config = new MentalConfig();

        List<String> warnings = config.reload(empty());

        assertTrue(warnings.isEmpty(), () -> "unexpected warnings: " + warnings);
        assertEquals(HitRegSettings.DEFAULTS, config.hitReg());
        assertEquals(WtapSettings.DEFAULTS, config.wtap());
        assertTrue(config.wtap().enabled(), "in-order sprint reads are the era default");
        assertEquals(KnockbackSettings.DEFAULTS, config.knockback());
        assertEquals(CompensationSettings.DEFAULTS, config.compensation());
        assertEquals(FishingKnockbackSettings.DEFAULTS, config.fishingKnockback());
        assertEquals(RodVelocitySettings.DEFAULTS, config.rodVelocity());
        assertEquals(ProjectileKnockbackSettings.DEFAULTS, config.projectileKnockback());
        assertEquals(AnticheatSettings.DEFAULTS, config.anticheat());
        assertEquals(CompatibilitySettings.DEFAULTS, config.compatibility());
        assertEquals(DebugSettings.DEFAULTS, config.debug());
        assertEquals(CraftingSettings.DEFAULTS, config.crafting());
        assertEquals(OffhandSettings.DEFAULTS, config.offhand());
        assertEquals(GoldenAppleSettings.DEFAULTS, config.goldenApple());
        assertEquals(EnderPearlSettings.DEFAULTS, config.enderPearl());
        assertEquals(RegenSettings.DEFAULTS, config.regen());
        assertEquals(ArmourStrengthSettings.DEFAULTS, config.armourStrength());
        assertEquals(ArmourDurabilitySettings.DEFAULTS, config.armourDurability());
        assertEquals(PotionDurationSettings.DEFAULTS, config.potionDuration());
        assertEquals(PotionValueSettings.DEFAULTS, config.potionValues());
        assertFalse(config.potionValues().enabled(), "old-potion-values defaults OFF");
        assertEquals(CritSettings.DEFAULTS, config.crit());
        assertFalse(config.crit().enabled(), "old-critical-hits defaults OFF");
        assertEquals(ToolDurabilitySettings.DEFAULTS, config.toolDurability());
        assertFalse(config.toolDurability().enabled(), "old-tool-durability defaults OFF");
        assertEquals(SwordBlockingSettings.DEFAULTS, config.swordBlocking());
        assertFalse(config.swordBlocking().enabled(), "sword-blocking defaults OFF");
        assertEquals(HitboxSettings.DEFAULTS, config.hitbox());
        assertFalse(config.hitbox().enabled(), "old-hitboxes defaults OFF");
    }

    @Test
    void bundledFilesMatchRecordDefaults() {
        MentalConfig config = new MentalConfig();

        List<String> warnings = config.reload(bundled());

        assertTrue(warnings.isEmpty(), () -> "unexpected warnings: " + warnings);
        assertEquals(HitRegSettings.DEFAULTS, config.hitReg());
        assertEquals(WtapSettings.DEFAULTS, config.wtap());
        assertEquals(CompensationSettings.DEFAULTS, config.compensation());
        assertEquals(FishingKnockbackSettings.DEFAULTS, config.fishingKnockback());
        assertEquals(RodVelocitySettings.DEFAULTS, config.rodVelocity());
        assertEquals(ProjectileKnockbackSettings.DEFAULTS, config.projectileKnockback());
        assertEquals(AnticheatSettings.DEFAULTS, config.anticheat());
        assertEquals(CompatibilitySettings.DEFAULTS, config.compatibility());
        assertEquals(DebugSettings.DEFAULTS, config.debug());
        assertEquals(CraftingSettings.DEFAULTS, config.crafting());
        assertEquals(OffhandSettings.DEFAULTS, config.offhand());
        assertEquals(GoldenAppleSettings.DEFAULTS, config.goldenApple());
        assertEquals(EnderPearlSettings.DEFAULTS, config.enderPearl());
        assertEquals(RegenSettings.DEFAULTS, config.regen());
        assertEquals(ArmourStrengthSettings.DEFAULTS, config.armourStrength());
        assertEquals(ArmourDurabilitySettings.DEFAULTS, config.armourDurability());
        assertEquals(PotionDurationSettings.DEFAULTS, config.potionDuration());
        assertEquals(PotionValueSettings.DEFAULTS, config.potionValues());
        assertEquals(CritSettings.DEFAULTS, config.crit());
        assertEquals(ToolDurabilitySettings.DEFAULTS, config.toolDurability());
        assertEquals(SwordBlockingSettings.DEFAULTS, config.swordBlocking());
        assertEquals(HitboxSettings.DEFAULTS, config.hitbox());

        KnockbackSettings knockback = config.knockback();
        assertTrue(knockback.enabled());
        assertEquals("legacy-1.7", knockback.defaultProfile());
        assertTrue(knockback.perWorld().isEmpty());
        assertEquals(
                Set.copyOf(ConfigStore.BUNDLED_PROFILES),
                knockback.profiles().keySet());
        // The shipped default file IS the built-in constant, byte for byte.
        assertEquals(KnockbackProfile.LEGACY_17, knockback.byName("legacy-1.7"));
    }

    /**
     * The preset era pins: each bundled profile parses to the values the
     * research established, so a regenerated preset can never drift.
     */
    @Test
    void bundledPresetsCarryTheirCanonicalValues() {
        MentalConfig config = new MentalConfig();
        config.reload(bundled());
        Map<String, KnockbackProfile> profiles = config.knockback().profiles();

        KnockbackProfile legacy17 = profiles.get("legacy-1.7");
        assertNotNull(legacy17);
        // The full tracker stamp, NOT the later-joiner decay: vanilla's
        // tracker wire was connection-order bimodal, and the dominant mode
        // shipped undecayed (measured on real 1.7.10, both join orders).
        assertEquals(KnockbackDelivery.TRACKER, legacy17.meleeDelivery());
        assertEquals(KnockbackDelivery.TRACKER, legacy17.projectileDelivery());

        KnockbackProfile legacy18 = profiles.get("legacy-1.8");
        assertNotNull(legacy18);
        assertFalse(legacy18.combos());
        assertEquals(ResistancePolicy.LEGACY, legacy18.resistance());
        assertEquals(KnockbackProfile.LEGACY_17.base(), legacy18.base());
        assertEquals(KnockbackProfile.LEGACY_17.extra(), legacy18.extra());
        assertEquals(KnockbackDelivery.IMMEDIATE, legacy18.meleeDelivery());
        assertEquals(KnockbackDelivery.TRACKER, legacy18.projectileDelivery());

        // kohi == the archived kohi2016 values (friction divisor 2.0 → 0.5),
        // on the 1.7.10 era model: ledger combos, tracker wire, no
        // resistance roll (the 1.7 item pool had nothing resistant).
        KnockbackProfile kohi = profiles.get("kohi");
        assertNotNull(kohi);
        assertEquals(new KnockbackProfile.Push(0.35, 0.35), kohi.base());
        assertEquals(new KnockbackProfile.Push(0.425, 0.085), kohi.extra());
        assertEquals(new KnockbackProfile.Friction(0.5, 0.5, 0.5), kohi.friction());
        assertEquals(0.4, kohi.limits().vertical());
        assertEquals(VerticalMode.ADD, kohi.verticalMode());
        assertTrue(kohi.combos());
        assertEquals(KnockbackDelivery.TRACKER, kohi.meleeDelivery());
        assertEquals(KnockbackDelivery.TRACKER, kohi.projectileDelivery());
        assertEquals(ResistancePolicy.NONE, kohi.resistance());

        // mmc == the archived dev123.minemen.club (2017) values, confirmed
        // byte-identical across two independent archives: friction divisor
        // 1.8 → 0.5556, vanilla ADD shape, full vanilla sprint bonus. The
        // remake-derived SET vertical and distance taper are superseded.
        KnockbackProfile mmc = profiles.get("mmc");
        assertNotNull(mmc);
        assertEquals(KnockbackDelivery.IMMEDIATE, mmc.meleeDelivery());
        assertEquals(KnockbackDelivery.TRACKER, mmc.projectileDelivery());
        assertEquals(VerticalMode.ADD, mmc.verticalMode());
        assertEquals(new KnockbackProfile.Push(0.32, 0.32), mmc.base());
        assertEquals(new KnockbackProfile.Push(0.5, 0.1), mmc.extra());
        assertEquals(new KnockbackProfile.Friction(0.5556, 0.5556, 0.5556), mmc.friction());
        assertFalse(mmc.rangeReduction().enabled());
        assertEquals(0.4, mmc.limits().vertical());
        assertFalse(mmc.combos());
        assertEquals(ResistancePolicy.NONE, mmc.resistance());

        // lunar == the archived Lunar S5 values, confirmed byte-identical
        // across two independent archives: split friction (÷1.46 h, ÷1.31 v),
        // heavy base, weak sprint differential, cap below the base vertical.
        KnockbackProfile lunar = profiles.get("lunar");
        assertNotNull(lunar);
        assertEquals(new KnockbackProfile.Push(0.54, 0.44), lunar.base());
        assertEquals(new KnockbackProfile.Push(0.38, 0.0), lunar.extra());
        assertEquals(new KnockbackProfile.Friction(0.6849, 0.7634, 0.6849), lunar.friction());
        assertEquals(0.361735, lunar.limits().vertical());
        assertFalse(lunar.combos());
        assertEquals(KnockbackDelivery.IMMEDIATE, lunar.meleeDelivery());
        assertEquals(KnockbackDelivery.TRACKER, lunar.projectileDelivery());
        assertEquals(ResistancePolicy.NONE, lunar.resistance());

        // minehq == the archived MineHQ values: between kohi and vanilla,
        // 1.7.10 era model like kohi.
        KnockbackProfile minehq = profiles.get("minehq");
        assertNotNull(minehq);
        assertEquals(new KnockbackProfile.Push(0.36, 0.36), minehq.base());
        assertEquals(new KnockbackProfile.Push(0.45, 0.09), minehq.extra());
        assertEquals(new KnockbackProfile.Friction(0.5, 0.5, 0.5), minehq.friction());
        assertEquals(0.4, minehq.limits().vertical());
        assertTrue(minehq.combos());
        assertEquals(KnockbackDelivery.TRACKER, minehq.meleeDelivery());

        // badlion == the archived NoDebuff/PotPvP values (both archives):
        // softest base of the practice set, on the 1.7 back-end Badlion ran
        // through its NoDebuff prime (ledger combos, tracker wire).
        KnockbackProfile badlion = profiles.get("badlion");
        assertNotNull(badlion);
        assertEquals(new KnockbackProfile.Push(0.34, 0.34), badlion.base());
        assertEquals(new KnockbackProfile.Push(0.48, 0.085), badlion.extra());
        assertEquals(new KnockbackProfile.Friction(0.5, 0.5, 0.5), badlion.friction());
        assertEquals(0.4, badlion.limits().vertical());
        assertTrue(badlion.combos());
        assertEquals(KnockbackDelivery.TRACKER, badlion.meleeDelivery());

        // velt == the archived VeltPvP values: friction divisor 10 → 0.1
        // residual wipe, fixed 0.36 vertical (cap == base), zero sprint
        // vertical — the late-era "dead consistent" practice shape.
        KnockbackProfile velt = profiles.get("velt");
        assertNotNull(velt);
        assertEquals(new KnockbackProfile.Push(0.325, 0.36), velt.base());
        assertEquals(new KnockbackProfile.Push(0.5, 0.0), velt.extra());
        assertEquals(new KnockbackProfile.Friction(0.1, 0.1, 0.1), velt.friction());
        assertEquals(0.36, velt.limits().vertical());
        assertFalse(velt.combos());
        // velt does not correct the airborne over-travel — its air multiplier
        // is identity. signature is the derivative that does.
        assertEquals(new KnockbackProfile.Push(1.0, 1.0), velt.air());

        // signature == Mental's own velt derivative: every velt value verbatim
        // EXCEPT air.horizontal 1.0 -> 0.92, which trims the airborne combo
        // hits (the second and later) 8% to hold the reach pocket. The
        // grounded opener and the pinned 0.36 vertical are untouched.
        KnockbackProfile signature = profiles.get("signature");
        assertNotNull(signature);
        assertEquals(velt.base(), signature.base());
        assertEquals(velt.extra(), signature.extra());
        assertEquals(velt.friction(), signature.friction());
        assertEquals(velt.limits(), signature.limits());
        assertEquals(velt.verticalMode(), signature.verticalMode());
        assertEquals(velt.combos(), signature.combos());
        assertEquals(velt.meleeDelivery(), signature.meleeDelivery());
        assertEquals(velt.resistance(), signature.resistance());
        assertEquals(new KnockbackProfile.Push(0.92, 1.0), signature.air());

        // custom ships as legacy-1.7 values — selecting it changes nothing
        // until the owner edits the file.
        KnockbackProfile custom = profiles.get("custom");
        assertNotNull(custom);
        assertTrue(custom.sameValues(KnockbackProfile.LEGACY_17));
    }

    /* ------------------------------------------------------------------ */
    /*  Selection and validation                                           */
    /* ------------------------------------------------------------------ */

    @Test
    void profileSelectionAndPerWorldResolveAgainstLoadedProfiles() throws Exception {
        MentalConfig config = new MentalConfig();
        Map<String, ConfigurationSection> profiles = Map.of(
                "arena", yaml("""
                        display-name: Arena
                        knockback:
                          base:
                            horizontal: 0.5
                        """));

        List<String> warnings = config.reload(sources(
                "", """
                knockback:
                  profile: arena
                  per-world:
                    duels: arena
                    lobby: nonexistent
                """, "", "", profiles));

        KnockbackSettings knockback = config.knockback();
        assertEquals("arena", knockback.defaultProfile());
        assertEquals(0.5, knockback.profile().base().horizontal());
        assertEquals("Arena", knockback.profile().displayName());
        assertEquals(Map.of("duels", "arena"), knockback.perWorld());
        // legacy-1.7 is always present even though no file provided it.
        assertEquals(KnockbackProfile.LEGACY_17, knockback.byName("legacy-1.7"));
        assertEquals(1, warnings.size(), () -> "warnings: " + warnings);
        assertTrue(warnings.get(0).contains("per-world.lobby"));
    }

    @Test
    void unknownSelectedProfileWarnsAndFallsBackToLegacy() throws Exception {
        MentalConfig config = new MentalConfig();

        List<String> warnings = config.reload(sources(
                "", "knockback:\n  profile: minemen-exact\n", "", "", Map.of()));

        assertEquals("legacy-1.7", config.knockback().defaultProfile());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("minemen-exact"));
    }

    @Test
    void moduleSwitchesLiveInMainConfig() throws Exception {
        MentalConfig config = new MentalConfig();

        List<String> warnings = config.reload(sources(
                """
                modules:
                  knockback: false
                  rod-velocity: false
                  wtap-registration: false
                """, "", "", "", Map.of()));

        assertTrue(warnings.isEmpty(), () -> "unexpected warnings: " + warnings);
        assertFalse(config.knockback().enabled());
        assertFalse(config.rodVelocity().enabled());
        assertFalse(config.wtap().enabled());
        assertTrue(config.hitReg().enabled());
        assertTrue(config.fishingKnockback().enabled());
    }

    @Test
    void oldPotionValuesFlagTogglesOn() throws Exception {
        MentalConfig config = new MentalConfig();

        List<String> warnings = config.reload(sources(
                """
                modules:
                  old-potion-values: true
                """, "", "", "", Map.of()));

        assertTrue(warnings.isEmpty(), () -> "unexpected warnings: " + warnings);
        assertTrue(config.potionValues().enabled());
    }

    @Test
    void oldCriticalHitsFlagTogglesOn() throws Exception {
        MentalConfig config = new MentalConfig();

        List<String> warnings = config.reload(sources(
                """
                modules:
                  old-critical-hits: true
                """, "", "", "", Map.of()));

        assertTrue(warnings.isEmpty(), () -> "unexpected warnings: " + warnings);
        assertTrue(config.crit().enabled());
    }

    @Test
    void oldToolDurabilityFlagTogglesOn() throws Exception {
        MentalConfig config = new MentalConfig();

        List<String> warnings = config.reload(sources(
                """
                modules:
                  old-tool-durability: true
                """, "", "", "", Map.of()));

        assertTrue(warnings.isEmpty(), () -> "unexpected warnings: " + warnings);
        assertTrue(config.toolDurability().enabled());
    }

    @Test
    void swordBlockingFlagTogglesOn() throws Exception {
        MentalConfig config = new MentalConfig();

        List<String> warnings = config.reload(sources(
                """
                modules:
                  sword-blocking: true
                """, "", "", "", Map.of()));

        assertTrue(warnings.isEmpty(), () -> "unexpected warnings: " + warnings);
        assertTrue(config.swordBlocking().enabled());
    }

    @Test
    void oldHitboxesFlagTogglesOn() throws Exception {
        MentalConfig config = new MentalConfig();

        List<String> warnings = config.reload(sources(
                """
                modules:
                  old-hitboxes: true
                """, "", "", "", Map.of()));

        assertTrue(warnings.isEmpty(), () -> "unexpected warnings: " + warnings);
        assertTrue(config.hitbox().enabled());
    }

    @Test
    void invalidValuesWarnAndFallBack() throws Exception {
        MentalConfig config = new MentalConfig();

        List<String> warnings = config.reload(sources(
                "", "", """
                hit-registration:
                  max-cps: -5
                  fast-path:
                    enabled: "definitely"
                """, """
                latency-compensation:
                  probe-strategy: carrier-pigeon
                """,
                Map.of("broken", yaml("""
                        knockback:
                          base:
                            horizontal: -3.0
                        """))));

        assertEquals(20, config.hitReg().maxCps());
        assertTrue(config.hitReg().fastPath());
        assertEquals(ProbeStrategy.PING, config.compensation().probeStrategy());
        KnockbackProfile broken = config.knockback().byName("broken");
        assertNotNull(broken);
        assertEquals(0.4, broken.base().horizontal());
        assertEquals(4, warnings.size(), () -> "warnings: " + warnings);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("hit-registration.yml")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("profiles/broken.yml")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("latency-compensation.yml")));
    }

    @Test
    void parsesCustomValuesIncludingDebugCategories() throws Exception {
        MentalConfig config = new MentalConfig();

        List<String> warnings = config.reload(sources(
                """
                modules:
                  knockback: false
                anticheat:
                  mode: force-safe
                  known: [GrimAC, Vulcan, Matrix]
                debug:
                  enabled: true
                  categories:
                    hitreg: true
                    packets: true
                    knockback: false
                """, """
                knockback:
                  profile: tuned
                fishing-knockback:
                  reel-in: cancel
                """, "", """
                latency-compensation:
                  probe-strategy: keepalive
                  ping-offset-ms: 10
                """,
                Map.of("tuned", yaml("""
                        knockback:
                          extra:
                            horizontal: 0.6
                          limits:
                            horizontal: 1.2
                          delivery:
                            melee: tracker-decayed
                          modifiers:
                            sprint: 2
                            combos: false
                            armor-resistance: scaling
                        """))));

        assertTrue(warnings.isEmpty(), () -> "unexpected warnings: " + warnings);
        assertFalse(config.knockback().enabled());
        KnockbackProfile tuned = config.knockback().profile();
        assertEquals("tuned", tuned.name());
        assertEquals(0.6, tuned.extra().horizontal());
        assertEquals(1.2, tuned.limits().horizontal());
        assertTrue(tuned.limits().limitsHorizontal());
        assertEquals(2.0, tuned.sprintFactor());
        assertFalse(tuned.combos());
        // the opt-in later-joiner wire parses; projectile stays the default
        assertEquals(KnockbackDelivery.TRACKER_DECAYED, tuned.meleeDelivery());
        assertEquals(KnockbackDelivery.TRACKER, tuned.projectileDelivery());
        assertEquals(ResistancePolicy.SCALING, tuned.resistance());
        assertEquals(ProbeStrategy.KEEPALIVE, config.compensation().probeStrategy());
        assertEquals(10, config.compensation().pingOffsetMillis());
        assertEquals(ReelInPolicy.CANCEL, config.fishingKnockback().reelIn());
        assertEquals(AnticheatMode.FORCE_SAFE, config.anticheat().mode());
        assertEquals(List.of("GrimAC", "Vulcan", "Matrix"), config.anticheat().knownPlugins());
        assertTrue(config.debug().enabled());
        assertEquals(Set.of(DebugCategory.HITREG, DebugCategory.PACKETS), config.debug().categories());
    }

    @Test
    void feedbackIntervalParsesAutoFixedAndLegacyValues() throws Exception {
        MentalConfig config = new MentalConfig();

        config.reload(sources("", "", """
                hit-registration:
                  fast-path:
                    feedback-min-interval-ms: auto
                """, "", Map.of()));
        assertEquals(HitRegSettings.FEEDBACK_INTERVAL_AUTO, config.hitReg().feedbackMinIntervalMillis());

        config.reload(sources("", "", """
                hit-registration:
                  fast-path:
                    feedback-min-interval-ms: 350
                """, "", Map.of()));
        assertEquals(350L, config.hitReg().feedbackMinIntervalMillis());

        // Configs written before the auto default keep their explicit number.
        config.reload(sources("", "", """
                hit-registration:
                  fast-path:
                    feedback-min-interval-ms: 500
                """, "", Map.of()));
        assertEquals(500L, config.hitReg().feedbackMinIntervalMillis());
    }

    @Test
    void compatibilitySectionParsesCoordinationModes() throws Exception {
        MentalConfig config = new MentalConfig();

        assertTrue(config.reload(sources(
                """
                compatibility:
                  old-combat-mechanics: ignore
                """, "", "", "", Map.of())).isEmpty());
        assertEquals(OcmCoordination.IGNORE, config.compatibility().oldCombatMechanics());
    }

    @Test
    void reloadPublishesFreshAtomicSnapshot() throws Exception {
        MentalConfig config = new MentalConfig();

        config.reload(empty());
        MentalConfig.Snapshot first = config.snapshot();
        config.reload(empty());
        MentalConfig.Snapshot second = config.snapshot();

        assertNotSame(first, second);
        assertEquals(first, second);
    }
}
