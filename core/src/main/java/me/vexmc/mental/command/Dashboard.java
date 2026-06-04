package me.vexmc.mental.command;

import me.vexmc.mental.MentalServices;
import me.vexmc.mental.engine.CombatModule;
import me.vexmc.mental.engine.ModuleRegistry;
import me.vexmc.mental.text.Brand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;
import org.jetbrains.annotations.NotNull;

/**
 * The interactive {@code /mental} overview: one line per module with its
 * live state, click-to-toggle and click-for-status controls, hover
 * descriptions, and the current anticheat posture.
 */
final class Dashboard {

    private Dashboard() {}

    static @NotNull Component render(@NotNull MentalServices services, @NotNull ModuleRegistry modules) {
        var builder = Component.text()
                .append(Brand.prefix())
                .append(Component.text(" Combat dashboard ", Brand.TEXT))
                .append(Component.text("(" + services.plugin().getDescription().getVersion() + ")", Brand.MUTED));

        for (CombatModule module : modules.all()) {
            builder.append(Component.newline()).append(moduleRow(module));
        }

        builder.append(Component.newline())
                .append(Component.text("  knockback profile: ", Brand.MUTED))
                .append(Component.text(services.config().knockback().defaultProfile(), Brand.TEXT)
                        .hoverEvent(HoverEvent.showText(
                                Component.text("Profiles overview", Brand.TEXT)))
                        .clickEvent(ClickEvent.runCommand("/mental kb")));

        builder.append(Component.newline())
                .append(Component.text("  anticheat: ", Brand.MUTED))
                .append(Component.text(services.anticheatGate().describe(), Brand.TEXT));

        builder.append(Component.newline())
                .append(Component.text("  ", Brand.MUTED))
                .append(link("/mental help", "Show every command"))
                .append(Component.text(" · ", Brand.MUTED))
                .append(link("/mental reload", "Reload the configuration"))
                .append(Component.text(" · ", Brand.MUTED))
                .append(link("/mental version", "Platform and capability report"));

        return builder.build();
    }

    private static Component moduleRow(CombatModule module) {
        boolean active = module.active();
        Component toggle = Component.text(active ? "[off]" : "[on]", active ? Brand.FAILURE : Brand.SUCCESS)
                .hoverEvent(HoverEvent.showText(Component.text(
                        (active ? "Disable " : "Enable ") + module.displayName(), Brand.TEXT)))
                .clickEvent(ClickEvent.runCommand(
                        "/mental module " + module.id() + " " + (active ? "off" : "on")));
        Component info = Component.text("[status]", Brand.ACCENT)
                .hoverEvent(HoverEvent.showText(Component.text("Show status", Brand.TEXT)))
                .clickEvent(ClickEvent.runCommand("/mental module " + module.id() + " status"));

        return Component.text()
                .append(Component.text("  " + (active ? "●" : "○") + " ", active ? Brand.SUCCESS : Brand.FAILURE))
                .append(Component.text(module.displayName(), Brand.SECONDARY)
                        .hoverEvent(HoverEvent.showText(Component.text(module.description(), Brand.TEXT))))
                .append(Component.text(" " + (active ? "enabled" : "disabled") + " ",
                        active ? Brand.SUCCESS : Brand.FAILURE))
                .append(toggle)
                .append(Component.space())
                .append(info)
                .build();
    }

    private static Component link(String command, String hover) {
        return Component.text(command, Brand.ACCENT)
                .decorate(TextDecoration.UNDERLINED)
                .hoverEvent(HoverEvent.showText(Component.text(hover, Brand.TEXT)))
                .clickEvent(ClickEvent.runCommand(command));
    }
}
