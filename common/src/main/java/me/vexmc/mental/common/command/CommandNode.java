package me.vexmc.mental.common.command;

import java.util.List;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One node of the declarative command tree — written once, rendered by both
 * the classic Bukkit executor and the Brigadier bridge.
 */
public sealed interface CommandNode permits LiteralNode, ArgumentNode {

    @NotNull String name();

    @Nullable String permission();

    @NotNull String description();

    @NotNull List<CommandNode> children();

    @Nullable CommandAction action();

    default boolean allowed(@NotNull CommandSender sender) {
        String permission = permission();
        return permission == null || sender.hasPermission(permission);
    }
}
