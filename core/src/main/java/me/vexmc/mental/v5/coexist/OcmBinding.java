package me.vexmc.mental.v5.coexist;

import java.lang.invoke.MethodHandle;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import me.vexmc.mental.kernel.coexist.ArbiterCore;
import me.vexmc.mental.kernel.coexist.CoexistWarnings;
import me.vexmc.mental.kernel.coexist.MechanicToken;
import org.bukkit.configuration.ConfigurationSection;

/**
 * The live OldCombatMechanics coordination state (the retired {@code OcmGate}
 * state machine), decoupled from module construction order and resolving
 * through the kernel {@link ArbiterCore}: the OCM compat feature writes it via
 * {@code bind}/{@code configOnly}/{@code clear}, and combat features read
 * {@code mentalOwns} at event time. Each state change constructs a fresh
 * arbiter and swaps the reference (volatile, so netty-thread reads see it).
 *
 * <p>The config scan is ported from the retired {@code OcmConfigScan}: OCM 2.x
 * modeset format (a module is possibly-handled unless disabled;
 * always-enabled or membership in a world-reachable modeset is a conservative
 * yes) with the OCM 1.x per-module {@code enabled} flag as the fallback.</p>
 */
public final class OcmBinding {

    private volatile ArbiterCore arbiter;
    private volatile CoexistWarnings.OcmFacts facts;

    public OcmBinding() {
        clear();
    }

    /**
     * Service-API mode: per-player modeset precision. {@code deciderLookup}
     * resolves the UUID to OCM's expected decider (a Player in production);
     * the handle is OCM's {@code isModuleEnabledForPlayer(decider, moduleKey)}.
     */
    public void bind(
            MethodHandle isModuleEnabledForPlayer,
            Function<UUID, Object> deciderLookup,
            Set<MechanicToken> knownToOcm,
            Set<MechanicToken> staticVerdicts,
            CoexistWarnings.OcmFacts facts) {
        ArbiterCore.BoundResolver resolver = (uuid, moduleKey) -> {
            Object decider = deciderLookup.apply(uuid);
            if (decider == null) {
                throw new IllegalStateException("no OCM decider for " + uuid);
            }
            try {
                return (boolean) isModuleEnabledForPlayer.invoke(decider, moduleKey);
            } catch (RuntimeException | Error direct) {
                throw direct;
            } catch (Throwable other) {
                throw new RuntimeException(other);
            }
        };
        this.arbiter = new ArbiterCore(ArbiterCore.Mode.BOUND, knownToOcm, staticVerdicts, resolver);
        this.facts = facts;
    }

    /** No-API mode: global conservative verdicts from OCM's config file. */
    public void configOnly(Set<MechanicToken> staticVerdicts, CoexistWarnings.OcmFacts facts) {
        this.arbiter = new ArbiterCore(
                ArbiterCore.Mode.CONFIG, EnumSet.noneOf(MechanicToken.class), staticVerdicts, null);
        this.facts = facts;
    }

    /** OCM absent or coordination disabled — Mental owns everything. */
    public void clear() {
        this.arbiter = new ArbiterCore(ArbiterCore.Mode.ABSENT,
                EnumSet.noneOf(MechanicToken.class), EnumSet.noneOf(MechanicToken.class), null);
        this.facts = new CoexistWarnings.OcmFacts(false, false, null, Set.of());
    }

    /** True when Mental owns {@code token} for {@code decider} under the current state. */
    public boolean mentalOwns(MechanicToken token, UUID decider) {
        return arbiter.mentalOwns(token, decider);
    }

    /** The startup coexistence warnings for the current facts and Mental's enabled rules. */
    public List<String> warnings(Set<MechanicToken> mentalEnabled) {
        return CoexistWarnings.derive(facts, mentalEnabled);
    }

    /* ------------------------------ config scan ------------------------------ */

    /** The arbitrated verdicts (the six) OCM's config says it could be handling. */
    public static Set<MechanicToken> scanVerdicts(ConfigurationSection ocmConfig) {
        boolean modeset = isModesetFormat(ocmConfig);
        Set<MechanicToken> handled = EnumSet.noneOf(MechanicToken.class);
        for (MechanicToken token : MechanicToken.values()) {
            if (token.arbitrated() && token.ocmKey() != null
                    && moduleEnabled(ocmConfig, token.ocmKey(), modeset)) {
                handled.add(token);
            }
        }
        return handled;
    }

    /** The full facts for the startup warnings: modeset default, playerDelay, enabled keys. */
    public static CoexistWarnings.OcmFacts scanFacts(ConfigurationSection ocmConfig) {
        boolean modeset = isModesetFormat(ocmConfig);
        Set<String> enabledKeys = new HashSet<>();
        for (MechanicToken token : MechanicToken.values()) {
            String key = token.ocmKey();
            if (key != null && moduleEnabled(ocmConfig, key, modeset)) {
                enabledKeys.add(key);
            }
        }
        boolean modesetKnockback = moduleEnabled(ocmConfig, "old-player-knockback", modeset);
        Integer playerDelay = ocmConfig.isSet("attack-frequency.playerDelay")
                ? ocmConfig.getInt("attack-frequency.playerDelay")
                : null;
        return new CoexistWarnings.OcmFacts(true, modesetKnockback, playerDelay, Set.copyOf(enabledKeys));
    }

    private static boolean isModesetFormat(ConfigurationSection config) {
        return config.isList("always_enabled_modules")
                || config.isList("disabled_modules")
                || config.isConfigurationSection("modesets");
    }

    private static boolean moduleEnabled(ConfigurationSection config, String module, boolean modeset) {
        return modeset ? modesetVerdict(config, module) : config.getBoolean(module + ".enabled", false);
    }

    private static boolean modesetVerdict(ConfigurationSection config, String module) {
        if (config.getStringList("disabled_modules").contains(module)) {
            return false;
        }
        if (config.getStringList("always_enabled_modules").contains(module)) {
            return true;
        }
        ConfigurationSection modesets = config.getConfigurationSection("modesets");
        if (modesets == null) {
            return false;
        }
        for (String name : reachableModesets(config, modesets)) {
            if (modesets.getStringList(name).contains(module)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> reachableModesets(ConfigurationSection config, ConfigurationSection modesets) {
        ConfigurationSection worlds = config.getConfigurationSection("worlds");
        if (worlds == null) {
            return modesets.getKeys(false);
        }
        Set<String> reachable = new HashSet<>();
        for (String world : worlds.getKeys(false)) {
            reachable.addAll(worlds.getStringList(world));
        }
        return reachable.isEmpty() ? modesets.getKeys(false) : reachable;
    }
}
