package me.vexmc.mental.v5.command;

import java.util.List;
import me.vexmc.mental.v5.MentalPluginV5;
import me.vexmc.mental.v5.gui.MenuManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * The {@code /mental} executor (spec §13). {@code reload} re-reads the
 * configuration (permission {@code mental.command.reload}); a bare {@code /mental}
 * from a permitted player ({@code mental.command.use}) opens the in-game
 * management menu, and from the console prints the reload hint. plugin.yml already
 * declares the command and its permissions; this only supplies the executor.
 */
public final class MentalCommand implements CommandExecutor {

    private static final String RELOAD_PERMISSION = "mental.command.reload";
    private static final String USE_PERMISSION = "mental.command.use";

    private final MentalPluginV5 plugin;
    private final MenuManager menus;

    public MentalCommand(@NotNull MentalPluginV5 plugin, @NotNull MenuManager menus) {
        this.plugin = plugin;
        this.menus = menus;
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

        // A bare /mental: a player opens the management menu; the console reloads.
        if (sender instanceof Player player) {
            if (!player.hasPermission(USE_PERMISSION)) {
                player.sendMessage("§cYou lack the permission " + USE_PERMISSION + ".");
                return true;
            }
            menus.openDashboard(player);
            return true;
        }

        sender.sendMessage("§7Mental v" + plugin.getDescription().getVersion()
                + " — the management menu is in-game only. Use §f/mental reload§7 to reload the configuration.");
        return true;
    }
}
