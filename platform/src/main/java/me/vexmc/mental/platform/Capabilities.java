package me.vexmc.mental.platform;

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
        boolean registryAttributes,
        boolean knockbackEvent) {

    public static @NotNull Capabilities detect() {
        boolean folia = classPresent("io.papermc.paper.threadedregions.RegionizedServer");
        boolean modernSchedulers =
                folia || classPresent("io.papermc.paper.threadedregions.scheduler.EntityScheduler");
        boolean brigadier = classPresent("io.papermc.paper.command.brigadier.Commands");
        boolean registryAttributes = !Attribute.class.isEnum();
        // Paper's modern knockback event (1.20.6+), the one mid-pass observers
        // (anticheats, SimpleBoxer) read; absent below it, where the mirror is a no-op.
        boolean knockbackEvent = classPresent("io.papermc.paper.event.entity.EntityKnockbackEvent");
        return new Capabilities(folia, modernSchedulers, brigadier, registryAttributes, knockbackEvent);
    }

    public @NotNull String describe() {
        return "folia=" + folia
                + " modernSchedulers=" + modernSchedulers
                + " brigadierCommands=" + brigadierCommands
                + " registryAttributes=" + registryAttributes
                + " knockbackEvent=" + knockbackEvent;
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
