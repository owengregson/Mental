package me.vexmc.mental;

import java.util.OptionalDouble;
import java.util.Set;
import me.vexmc.mental.api.Mental;
import me.vexmc.mental.engine.CombatModule;
import me.vexmc.mental.engine.ModuleRegistry;
import me.vexmc.mental.module.compensation.LatencyCompensationModule;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

record MentalApiImpl(@NotNull MentalPlugin plugin, @NotNull ModuleRegistry modules)
        implements Mental.MentalApi {

    @Override
    public boolean moduleEnabled(@NotNull String moduleId) {
        return modules.byId(moduleId).map(CombatModule::active).orElse(false);
    }

    @Override
    public @NotNull OptionalDouble pingMillis(@NotNull Player player) {
        return modules.byId("latency-compensation")
                .filter(LatencyCompensationModule.class::isInstance)
                .map(LatencyCompensationModule.class::cast)
                .map(compensation -> {
                    Double ping = compensation.pingStats(player.getUniqueId()).pingMillis();
                    return ping == null ? OptionalDouble.empty() : OptionalDouble.of(ping);
                })
                .orElse(OptionalDouble.empty());
    }

    @Override
    public @NotNull String knockbackProfile(@NotNull Player player) {
        return plugin.services().knockbackProfiles().resolve(player).name();
    }

    @Override
    public @NotNull String knockbackProfile() {
        return plugin.services().knockbackProfiles().defaultProfileName();
    }

    @Override
    public boolean setKnockbackProfile(@NotNull String profile) {
        return plugin.management().setGlobalProfile(profile);
    }

    @Override
    public @NotNull Set<String> knockbackProfiles() {
        return Set.copyOf(plugin.services().knockbackProfiles().names());
    }

    @Override
    public @NotNull String version() {
        return plugin.getDescription().getVersion();
    }
}
