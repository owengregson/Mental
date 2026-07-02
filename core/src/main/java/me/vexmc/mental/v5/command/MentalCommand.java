package me.vexmc.mental.v5.command;

import java.util.List;
import me.vexmc.mental.v5.MentalPluginV5;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.CommandExecutor;
import org.jetbrains.annotations.NotNull;

/**
 * The minimal v5 {@code /mental} executor (spec §13; the 4E command surface
 * pulled forward). {@code reload} re-reads the configuration (permission
 * {@code mental.command.reload}); anything else is a one-line placeholder — the
 * in-game management GUI arrives in Phase 6. plugin.yml already declares the
 * command and its permissions; this only supplies the executor.
 */
public final class MentalCommand implements CommandExecutor {

    private static final String RELOAD_PERMISSION = "mental.command.reload";

    private final MentalPluginV5 plugin;

    public MentalCommand(@NotNull MentalPluginV5 plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission(RELOAD_PERMISSION)) {
                sender.sendMessage("§cYou lack the permission " + RELOAD_PERMISSION + ".");
                return true;
            }
            List<String> issues = plugin.reloadAll();
            if (issues.isEmpty()) {
                sender.sendMessage("§aMental configuration reloaded.");
            } else {
                sender.sendMessage("§eMental configuration reloaded with "
                        + issues.size() + " warning(s):");
                for (String issue : issues) {
                    sender.sendMessage("§7 - " + issue);
                }
            }
            return true;
        }
        sender.sendMessage("§7Mental v" + plugin.getDescription().getVersion()
                + " — the in-game management menu arrives in a later release."
                + " Use §f/mental reload§7 to reload the configuration.");
        return true;
    }
}
