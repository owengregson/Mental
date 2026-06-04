package me.vexmc.mental.config;

import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;

/**
 * The parsed-but-untyped roots of every configuration file, gathered by the
 * {@link ConfigStore} and consumed by {@link MentalConfig#reload} in one
 * atomic pass.
 *
 * @param main     config.yml — module switches and plugin-wide policy
 * @param knockback knockback.yml — profile selection plus the rod, fishing
 *                  and projectile mechanics
 * @param hitReg   hit-registration.yml
 * @param latency  latency-compensation.yml
 * @param profiles profiles/&lt;name&gt;.yml roots, keyed by file stem
 */
public record ConfigSources(
        @NotNull ConfigurationSection main,
        @NotNull ConfigurationSection knockback,
        @NotNull ConfigurationSection hitReg,
        @NotNull ConfigurationSection latency,
        @NotNull Map<String, ConfigurationSection> profiles) {}
