package me.vexmc.mental;

import me.vexmc.mental.common.debug.DebugLog;
import me.vexmc.mental.common.platform.Capabilities;
import me.vexmc.mental.common.platform.ServerEnvironment;
import me.vexmc.mental.common.scheduling.Scheduling;
import me.vexmc.mental.config.MentalConfig;
import me.vexmc.mental.module.anticheat.AnticheatGate;
import me.vexmc.mental.module.knockback.KnockbackProfiles;
import me.vexmc.mental.module.knockback.SprintTracker;
import me.vexmc.mental.module.ocm.OcmGate;
import org.jetbrains.annotations.NotNull;

/** Everything a module needs, assembled once at boot. */
public record MentalServices(
        @NotNull MentalPlugin plugin,
        @NotNull MentalConfig config,
        @NotNull Capabilities capabilities,
        @NotNull ServerEnvironment environment,
        @NotNull Scheduling scheduling,
        @NotNull DebugLog debug,
        @NotNull AnticheatGate anticheatGate,
        @NotNull OcmGate ocmGate,
        @NotNull SprintTracker sprintTracker,
        @NotNull KnockbackProfiles knockbackProfiles) {}
