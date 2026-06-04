package me.vexmc.mental.common.platform;

import org.bukkit.attribute.Attribute;
import org.jetbrains.annotations.NotNull;

/**
 * Feature detection, computed once at boot.
 *
 * <p>Every optional code path in Mental keys off a capability rather than a
 * version comparison: a class either exists on this server or it does not.
 * Version numbers are reserved for the boot report and protocol decisions.</p>
 */
public record Capabilities(
        boolean folia,
        boolean modernSchedulers,
        boolean brigadierCommands,
        boolean registryAttributes) {

    public static @NotNull Capabilities detect() {
        boolean folia = classPresent("io.papermc.paper.threadedregions.RegionizedServer");
        boolean modernSchedulers =
                folia || classPresent("io.papermc.paper.threadedregions.scheduler.EntityScheduler");
        boolean brigadier = classPresent("io.papermc.paper.command.brigadier.Commands");
        boolean registryAttributes = !Attribute.class.isEnum();
        return new Capabilities(folia, modernSchedulers, brigadier, registryAttributes);
    }

    public @NotNull String describe() {
        return "folia=" + folia
                + " modernSchedulers=" + modernSchedulers
                + " brigadierCommands=" + brigadierCommands
                + " registryAttributes=" + registryAttributes;
    }

    private static boolean classPresent(@NotNull String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }
}
