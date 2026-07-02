package me.vexmc.mental.v5.coexist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import me.vexmc.mental.kernel.coexist.CoexistWarnings;
import me.vexmc.mental.kernel.coexist.MechanicToken;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * The OCM binding's state machine (bind/configOnly/clear) delegating to the
 * kernel arbiter, the config scan ported from the retired OcmConfigScan, and
 * the startup warnings wired from a real scan. The bound path is exercised with
 * a stub MethodHandle over a local test decider.
 */
class OcmBindingTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    /** A stand-in for OCM's Player argument, carrying the modules it has enabled. */
    private record FakeDecider(Set<String> enabledKeys) {}

    /** The stub OCM {@code isModuleEnabledForPlayer(player, moduleKey)}. */
    public static boolean ocmEnabled(FakeDecider decider, String moduleKey) {
        return decider.enabledKeys().contains(moduleKey);
    }

    private static MethodHandle stubHandle() throws Exception {
        return MethodHandles.lookup().findStatic(OcmBindingTest.class, "ocmEnabled",
                MethodType.methodType(boolean.class, FakeDecider.class, String.class));
    }

    private static YamlConfiguration yaml(String content) throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(content);
        return configuration;
    }

    /* ------------------------------ state machine ------------------------------ */

    @Test
    void clearAndAbsentMeanMentalOwnsEverything() {
        OcmBinding binding = new OcmBinding();
        binding.clear();
        for (MechanicToken token : MechanicToken.values()) {
            assertTrue(binding.mentalOwns(token, ALICE), () -> "ABSENT must own " + token);
        }
    }

    @Test
    void boundResolvesPerPlayerThroughTheHandle() throws Exception {
        Function<UUID, Object> lookup = uuid -> uuid.equals(ALICE)
                ? new FakeDecider(Set.of("old-player-knockback"))
                : new FakeDecider(Set.of());
        OcmBinding binding = new OcmBinding();
        binding.bind(stubHandle(), lookup,
                EnumSet.of(MechanicToken.MELEE_KNOCKBACK),
                EnumSet.noneOf(MechanicToken.class),
                absentFacts());

        // Alice's modeset has OCM melee KB on → Mental yields for her.
        assertFalse(binding.mentalOwns(MechanicToken.MELEE_KNOCKBACK, ALICE));
        // Bob's does not → Mental owns for him.
        assertTrue(binding.mentalOwns(MechanicToken.MELEE_KNOCKBACK, BOB));
        // A non-arbitrated token is always Mental's, whatever the modeset.
        assertTrue(binding.mentalOwns(MechanicToken.REGEN, ALICE));
    }

    @Test
    void configOnlyUsesStaticVerdictsGlobally() {
        OcmBinding binding = new OcmBinding();
        binding.configOnly(EnumSet.of(MechanicToken.MELEE_KNOCKBACK), absentFacts());
        assertFalse(binding.mentalOwns(MechanicToken.MELEE_KNOCKBACK, ALICE));
        assertFalse(binding.mentalOwns(MechanicToken.MELEE_KNOCKBACK, null));
        assertTrue(binding.mentalOwns(MechanicToken.FISHING_KNOCKBACK, ALICE));
    }

    /* -------------------------------- config scan -------------------------------- */

    @Test
    void ocmDefaultConfigYieldsEveryArbitratedMechanic() throws Exception {
        Set<MechanicToken> verdicts = OcmBinding.scanVerdicts(yaml("""
                always_enabled_modules:
                  - "attack-frequency"
                  - "old-fishing-knockback"
                  - "fishing-rod-velocity"
                  - "projectile-knockback"
                disabled_modules:
                  - "disable-offhand"
                modesets:
                  old:
                    - "disable-attack-cooldown"
                    - "old-tool-damage"
                    - "old-player-knockback"
                    - "old-critical-hits"
                  new:
                    - "old-golden-apples"
                worlds:
                  __default__: [ "old", "new" ]
                """));

        Set<MechanicToken> arbitrated = EnumSet.noneOf(MechanicToken.class);
        for (MechanicToken token : MechanicToken.values()) {
            if (token.arbitrated()) {
                arbitrated.add(token);
            }
        }
        assertEquals(arbitrated, verdicts);
    }

    @Test
    void disabledModulesAndUnreachableModesetsAreNotHandled() throws Exception {
        Set<MechanicToken> verdicts = OcmBinding.scanVerdicts(yaml("""
                always_enabled_modules:
                  - "old-fishing-knockback"
                disabled_modules:
                  - "projectile-knockback"
                  - "fishing-rod-velocity"
                modesets:
                  old:
                    - "old-player-knockback"
                  arena:
                    - "old-tool-damage"
                worlds:
                  __default__: [ "old" ]
                """));

        assertTrue(verdicts.contains(MechanicToken.FISHING_KNOCKBACK), "always-enabled is handled");
        assertTrue(verdicts.contains(MechanicToken.MELEE_KNOCKBACK), "reachable modeset is handled");
        assertFalse(verdicts.contains(MechanicToken.PROJECTILE_KNOCKBACK), "disabled wins");
        assertFalse(verdicts.contains(MechanicToken.FISHING_ROD_VELOCITY), "disabled wins");
        assertFalse(verdicts.contains(MechanicToken.TOOL_DAMAGE), "unreachable modeset");
        assertFalse(verdicts.contains(MechanicToken.CRITICAL_HITS), "never mentioned");
    }

    @Test
    void missingWorldsSectionReachesEveryModeset() throws Exception {
        Set<MechanicToken> verdicts = OcmBinding.scanVerdicts(yaml("""
                modesets:
                  anything:
                    - "old-player-knockback"
                """));
        assertTrue(verdicts.contains(MechanicToken.MELEE_KNOCKBACK));
    }

    @Test
    void legacyPerModuleFlagsAreHonoredWhenNoModesetStructureExists() throws Exception {
        Set<MechanicToken> verdicts = OcmBinding.scanVerdicts(yaml("""
                old-player-knockback:
                  enabled: true
                  knockback-horizontal: 0.4
                old-fishing-knockback:
                  enabled: false
                """));
        assertTrue(verdicts.contains(MechanicToken.MELEE_KNOCKBACK));
        assertFalse(verdicts.contains(MechanicToken.FISHING_KNOCKBACK));
        assertFalse(verdicts.contains(MechanicToken.TOOL_DAMAGE));
    }

    @Test
    void emptyConfigHandlesNothing() throws Exception {
        assertTrue(OcmBinding.scanVerdicts(yaml("")).isEmpty());
    }

    /* -------------------------------- warnings wiring -------------------------------- */

    @Test
    void warningsAreWiredFromARealScan() throws Exception {
        YamlConfiguration ocm = yaml("""
                always_enabled_modules:
                  - "old-player-regen"
                modesets:
                  old:
                    - "old-player-knockback"
                worlds:
                  __default__: [ "old" ]
                attack-frequency:
                  playerDelay: 18
                """);
        CoexistWarnings.OcmFacts facts = OcmBinding.scanFacts(ocm);

        OcmBinding binding = new OcmBinding();
        binding.configOnly(OcmBinding.scanVerdicts(ocm), facts);
        List<String> warnings = binding.warnings(EnumSet.of(MechanicToken.REGEN));

        // modeset old-player-knockback + playerDelay 18 + REGEN double-enable = 3.
        assertEquals(3, warnings.size(), () -> warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("old-player-knockback")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("18")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("old-player-regen")));
    }

    private static CoexistWarnings.OcmFacts absentFacts() {
        return new CoexistWarnings.OcmFacts(false, false, null, Set.of());
    }
}
