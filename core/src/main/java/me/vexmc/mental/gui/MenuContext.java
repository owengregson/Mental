package me.vexmc.mental.gui;

import me.vexmc.mental.MentalServices;
import me.vexmc.mental.debug.PlayerDebugSink;
import me.vexmc.mental.manage.ManagementService;
import org.jetbrains.annotations.NotNull;

/**
 * The shared dependencies every menu reads from: the service container (config
 * snapshot, scheduling, module registry via {@code services.plugin()}), the
 * write-back service for config mutations, and the per-player debug sink the
 * debug screen subscribes through. One instance lives for the plugin lifetime,
 * held by the {@link MenuManager}.
 */
public record MenuContext(
        @NotNull MentalServices services,
        @NotNull ManagementService management,
        @NotNull PlayerDebugSink debugSink) {}
