package me.vexmc.mental.v5.api;

import java.util.OptionalDouble;
import java.util.Set;
import me.vexmc.mental.api.Mental;
import me.vexmc.mental.kernel.wire.LatencyModel;
import me.vexmc.mental.v5.MentalPluginV5;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.manage.Management;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * The v5 implementation of the public {@link Mental.MentalApi} surface (spec
 * §11) — the successor to the retired {@code MentalApiImpl}. Registered in
 * {@code onEnable} both in the static {@link Mental} holder and the Bukkit
 * {@code ServicesManager}, and unregistered in {@code onDisable}.
 *
 * <p>Reads project onto the v5 seams: module enablement through the reconciler's
 * active state, the global profile through the config snapshot, ping through the
 * kernel {@link LatencyModel}. Profile mutation delegates to {@link Management},
 * so the API and the (Phase 6) GUI share one write-back path.</p>
 */
public record MentalFacade(
        @NotNull MentalPluginV5 plugin,
        @NotNull Management management,
        @NotNull LatencyModel latency) implements Mental.MentalApi {

    @Override
    public boolean moduleEnabled(@NotNull String moduleId) {
        return Feature.byModuleId(moduleId).map(plugin::featureActive).orElse(false);
    }

    @Override
    public @NotNull OptionalDouble pingMillis(@NotNull Player player) {
        Double ping = latency.forPlayer(player.getUniqueId()).pingMillis();
        return ping == null ? OptionalDouble.empty() : OptionalDouble.of(ping);
    }

    @Override
    public @NotNull String knockbackProfile(@NotNull Player player) {
        return plugin.snapshot().profileFor(player.getWorld().getName()).name();
    }

    @Override
    public @NotNull String knockbackProfile() {
        return plugin.snapshot().defaultProfile();
    }

    @Override
    public boolean setKnockbackProfile(@NotNull String profile) {
        return management.setGlobalProfile(profile);
    }

    @Override
    public @NotNull Set<String> knockbackProfiles() {
        return Set.copyOf(plugin.snapshot().profileNames());
    }

    @Override
    public @NotNull String version() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public int apiVersion() {
        return 2;
    }
}
