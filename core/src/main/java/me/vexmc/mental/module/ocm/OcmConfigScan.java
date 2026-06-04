package me.vexmc.mental.module.ocm;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;

/**
 * Global mechanic verdicts from OldCombatMechanics' own config file — the
 * fallback when its service API is unavailable, and the answer for
 * interactions with no player decider.
 *
 * <p>Reads the modeset format (OCM 2.x): a module is treated as possibly
 * OCM-handled unless it is in {@code disabled_modules}; {@code
 * always_enabled_modules} is a definite yes, and membership in any modeset
 * allowed in any world is a conservative yes — without the per-player API
 * there is no way to know which players run that modeset, and yielding is
 * strictly safer than double-applying. Configs predating modesets (OCM 1.x)
 * carried a per-module {@code enabled} flag instead, which is honored when no
 * modeset structure exists.</p>
 */
final class OcmConfigScan {

    private OcmConfigScan() {}

    static @NotNull Set<OcmMechanic> verdicts(@NotNull ConfigurationSection ocmConfig) {
        Set<OcmMechanic> handled = EnumSet.noneOf(OcmMechanic.class);
        boolean modesetFormat = ocmConfig.isList("always_enabled_modules")
                || ocmConfig.isList("disabled_modules")
                || ocmConfig.isConfigurationSection("modesets");

        for (OcmMechanic mechanic : OcmMechanic.values()) {
            boolean verdict = modesetFormat
                    ? modesetVerdict(ocmConfig, mechanic.ocmName())
                    : ocmConfig.getBoolean(mechanic.ocmName() + ".enabled", false);
            if (verdict) {
                handled.add(mechanic);
            }
        }
        return handled;
    }

    private static boolean modesetVerdict(@NotNull ConfigurationSection config, @NotNull String module) {
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
        Set<String> reachable = reachableModesets(config, modesets);
        for (String name : reachable) {
            if (modesets.getStringList(name).contains(module)) {
                return true;
            }
        }
        return false;
    }

    /** Modesets allowed in at least one world; all of them when no worlds section restricts. */
    private static @NotNull Set<String> reachableModesets(
            @NotNull ConfigurationSection config, @NotNull ConfigurationSection modesets) {
        ConfigurationSection worlds = config.getConfigurationSection("worlds");
        if (worlds == null) {
            return modesets.getKeys(false);
        }
        Set<String> reachable = new HashSet<>();
        for (String world : worlds.getKeys(false)) {
            List<String> allowed = worlds.getStringList(world);
            reachable.addAll(allowed);
        }
        return reachable.isEmpty() ? modesets.getKeys(false) : reachable;
    }
}
