package me.vexmc.mental.command;

import java.util.List;
import me.vexmc.mental.common.command.CommandTree;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

/** Classic command backend — works on every server in the supported range. */
public record BukkitCommandRenderer(@NotNull CommandTree tree) implements CommandExecutor, TabCompleter {

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            String @NotNull [] args) {
        tree.execute(sender, args);
        return true;
    }

    @Override
    public @NotNull List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            String @NotNull [] args) {
        return tree.complete(sender, args);
    }
}
