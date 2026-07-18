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
 * configuration (permission {@code mental.command.reload}); {@code debug subscribe}
 * toggles streaming verbose debug lines to the caller's chat (permission {@code
 * mental.command.debug}); a bare {@code /mental} from a permitted player ({@code
 * mental.command.use}) opens the in-game management menu, and from the console
 * prints the reload hint. plugin.yml already declares the command and its
 * permissions; this only supplies the executor.
 */
public final class MentalCommand implements CommandExecutor {

    private static final String RELOAD_PERMISSION = "mental.command.reload";
    private static final String USE_PERMISSION = "mental.command.use";
    private static final String DEBUG_PERMISSION = "mental.command.debug";

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

        if (args.length > 0 && args[0].equalsIgnoreCase("debug")) {
            return handleDebug(sender, args);
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

    /**
     * {@code /mental debug subscribe} — toggles routing verbose debug lines to the
     * caller's own chat (the player-facing sink). Player-only (the lines are chat
     * output) and permission-gated; the subscription also clears on quit.
     */
    private boolean handleDebug(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§7/mental debug subscribe streams debug output to a player's chat — run it in-game.");
            return true;
        }
        if (!player.hasPermission(DEBUG_PERMISSION)) {
            player.sendMessage("§cYou lack the permission " + DEBUG_PERMISSION + ".");
            return true;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("subscribe")) {
            player.sendMessage("§7Usage: §f/mental debug subscribe§7 — toggle streaming debug lines to your chat.");
            return true;
        }
        boolean nowSubscribed = plugin.playerDebugSink().toggle(player);
        if (nowSubscribed) {
            player.sendMessage("§aNow streaming Mental debug output to your chat. "
                    + "Run it again to stop; it clears automatically when you quit.");
            // A subscription is silent until the master switch and at least one
            // channel are live — say so, so an admin is not left wondering.
            if (!plugin.snapshot().debug().enabled() || plugin.snapshot().debug().categories().isEmpty()) {
                player.sendMessage("§7No debug channels are active yet — enable them in §f/mental§7 › Debug.");
            }
        } else {
            player.sendMessage("§eStopped streaming Mental debug output to your chat.");
        }
        return true;
    }
}
