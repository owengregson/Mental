package me.vexmc.mental.common.command;

import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Everything an executed command sees: the sender and its captured arguments. */
public record CommandContext(@NotNull CommandSender sender, @NotNull Map<String, String> arguments) {

    public @NotNull String arg(@NotNull String name) {
        String value = arguments.get(name);
        if (value == null) {
            throw new IllegalStateException("Argument '" + name + "' was not captured on this path");
        }
        return value;
    }

    public @Nullable Player playerSender() {
        return sender instanceof Player player ? player : null;
    }

    public void reply(@NotNull Component message) {
        sender.sendMessage(message);
    }
}
