package me.vexmc.mental.text;

import static me.vexmc.mental.text.Brand.ACCENT;
import static me.vexmc.mental.text.Brand.FAILURE;
import static me.vexmc.mental.text.Brand.MUTED;
import static me.vexmc.mental.text.Brand.SUCCESS;
import static me.vexmc.mental.text.Brand.TEXT;
import static me.vexmc.mental.text.Brand.failure;
import static me.vexmc.mental.text.Brand.line;
import static me.vexmc.mental.text.Brand.prefix;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.jetbrains.annotations.NotNull;

/**
 * All user-facing copy in one place; command nodes stay logic-only.
 */
public final class Messages {

    private Messages() {}

    public static @NotNull Component noPermission() {
        return failure("You do not have permission for that.");
    }

    public static @NotNull Component usage(@NotNull String usage) {
        return Component.text()
                .append(prefix())
                .append(Component.text(" Usage: ", MUTED))
                .append(Component.text(usage, ACCENT))
                .build();
    }

    public static @NotNull Component unknownSubcommand() {
        return Component.text()
                .append(prefix())
                .append(Component.text(" Unknown subcommand. Try ", MUTED))
                .append(Component.text("/mental help", ACCENT))
                .build();
    }

    public static @NotNull Component reloadSucceeded(long millis, @NotNull List<String> warnings) {
        var builder = Component.text()
                .append(prefix())
                .append(Component.text(" Reloaded in ", TEXT))
                .append(Component.text(millis + "ms", ACCENT))
                .append(Component.text(".", TEXT));
        if (!warnings.isEmpty()) {
            builder.append(Component.text(" " + warnings.size() + " config warning(s) — see console.", FAILURE));
        }
        return builder.build();
    }

    public static @NotNull Component reloadFailed(@NotNull String reason) {
        return failure("Reload failed: " + reason);
    }

    public static @NotNull Component moduleToggled(@NotNull String displayName, boolean enabled) {
        return enabled
                ? Brand.success(displayName + " enabled.")
                : failure(displayName + " disabled.");
    }

    public static @NotNull Component moduleUnknown(@NotNull String id, @NotNull List<String> knownIds) {
        return Component.text()
                .append(prefix())
                .append(Component.text(" Unknown module '", MUTED))
                .append(Component.text(id, FAILURE))
                .append(Component.text("'. Modules: ", MUTED))
                .append(Component.text(String.join(", ", knownIds), ACCENT))
                .build();
    }

    public static @NotNull Component moduleStatus(@NotNull String displayName, boolean active, @NotNull String description) {
        return Component.text()
                .append(prefix())
                .append(Component.text(" " + displayName + ": ", TEXT))
                .append(Component.text(active ? "ENABLED" : "DISABLED",
                        active ? SUCCESS : FAILURE, TextDecoration.BOLD))
                .append(Component.newline())
                .append(Component.text("  " + description, MUTED))
                .build();
    }

    public static @NotNull Component playersOnly() {
        return failure("That subcommand is player-only.");
    }

    public static @NotNull Component playerNotFound(@NotNull String name) {
        return failure("Player '" + name + "' is not online.");
    }

    public static @NotNull Component line(@NotNull String text) {
        return Brand.line(text);
    }
}
