package me.vexmc.mental.v5.gui;

import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.v5.MentalPluginV5;
import me.vexmc.mental.v5.manage.Management;
import org.jetbrains.annotations.NotNull;

/**
 * The shared dependencies every menu reads from: the live plugin (the config
 * snapshot, the reconciler's active-feature truth, the machine overlay, the boot
 * environment), and the {@link Management} write-back seam every
 * mutation flows through. One instance lives for the plugin lifetime, held by the
 * {@link MenuManager}.
 *
 * <p>The v5 replacement for the retired {@code gui/MenuContext}: it holds the
 * plugin directly rather than a service container, because in v5 the plugin
 * <em>is</em> the container (snapshot, scheduling, featureActive, overlay all
 * hang off it). Reads go through the snapshot and the reconciler; every write
 * goes through {@link Management} (or the machine overlay for the always-on config
 * sections), never the human YAML.</p>
 */
public record MenuContext(@NotNull MentalPluginV5 plugin, @NotNull Management management) {

    /** The region-correct scheduling surface menus dispatch their inventory work on. */
    public @NotNull Scheduling scheduling() {
        return plugin.scheduling();
    }
}
