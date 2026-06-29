package me.vexmc.mental.command;

import static me.vexmc.mental.common.command.LiteralNode.literal;

import java.util.List;
import me.vexmc.mental.MentalPlugin;
import me.vexmc.mental.common.command.CommandContext;
import me.vexmc.mental.common.command.CommandMessages;
import me.vexmc.mental.common.command.CommandTree;
import me.vexmc.mental.common.command.LiteralNode;
import me.vexmc.mental.gui.MenuManager;
import me.vexmc.mental.text.Brand;
import me.vexmc.mental.text.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * The {@code /mental} command — a thin launcher for the management GUI, not a
 * management surface in its own right.
 *
 * <p>Mental's administration moved entirely into the in-game menu: a player who
 * runs {@code /mental} opens the dashboard, and everything that used to be a
 * subcommand — module toggles, knockback-profile selection, debug controls,
 * the ping readout — is a screen there. The one surviving subcommand is {@code
 * reload}: the console cannot open an inventory and ops automation depends on a
 * scriptable reload, so it stays. The tree is still rendered by whichever
 * backend the server supports (classic / Brigadier), unchanged.</p>
 */
public final class MentalCommands {

    private static final String PERMISSION_USE = "mental.command.use";
    private static final String PERMISSION_RELOAD = "mental.command.reload";

    private final MentalPlugin plugin;
    private final MenuManager menus;

    public MentalCommands(@NotNull MentalPlugin plugin, @NotNull MenuManager menus) {
        this.plugin = plugin;
        this.menus = menus;
    }

    public @NotNull CommandTree build() {
        LiteralNode root = literal("mental")
                .permission(PERMISSION_USE)
                .describe("Open the Mental management menu.")
                .runs(this::open)
                .then(literal("reload")
                        .permission(PERMISSION_RELOAD)
                        .describe("Reload the configuration atomically.")
                        .runs(this::reload)
                        .build())
                .build();

        return new CommandTree(root, new CommandMessages() {
            @Override
            public @NotNull Component noPermission() {
                return Messages.noPermission();
            }

            @Override
            public @NotNull Component unknownSubcommand() {
                return Messages.unknownSubcommand();
            }

            @Override
            public @NotNull Component usage(@NotNull String usage) {
                return Messages.usage(usage);
            }
        });
    }

    private void open(@NotNull CommandContext context) {
        Player player = context.playerSender();
        if (player == null) {
            context.reply(Brand.info("Mental is managed through the in-game menu — run "
                    + "/mental as a player to open it. From the console use /mental reload."));
            return;
        }
        menus.openDashboard(player);
    }

    private void reload(@NotNull CommandContext context) {
        long started = System.nanoTime();
        try {
            List<String> warnings = plugin.reloadAll();
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
            context.reply(Messages.reloadSucceeded(elapsedMillis, warnings));
        } catch (Exception failure) {
            context.reply(Messages.reloadFailed(failure.getMessage() == null
                    ? failure.getClass().getSimpleName()
                    : failure.getMessage()));
        }
    }
}
