package me.vexmc.mental.common.command;

import java.util.List;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

/** Produces completion candidates for an argument node. */
@FunctionalInterface
public interface Suggester {

    Suggester NONE = (sender, partial) -> List.of();

    @NotNull List<String> suggest(@NotNull CommandSender sender, @NotNull String partial);
}
