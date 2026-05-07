package me.vexmc.strikesync.command;

import me.vexmc.strikesync.command.nodes.AuthorsNode;
import me.vexmc.strikesync.command.nodes.HelpNode;
import me.vexmc.strikesync.command.nodes.PingNode;
import me.vexmc.strikesync.command.nodes.ReloadNode;
import me.vexmc.strikesync.command.nodes.ToggleNode;
import me.vexmc.strikesync.command.nodes.compensation.CompensationNode;
import me.vexmc.strikesync.command.nodes.knockback.KnockbackNode;
import me.vexmc.strikesync.core.StrikeSyncService;
import me.vexmc.strikesync.message.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Root command for {@code /strikesync} (alias {@code /ss}).
 *
 * <p>
 * Holds the immutable child map and routes every invocation through the
 * tree. Tab completion is delegated to the relevant
 * {@link CommandNode#complete}
 * implementation so individual subcommands can offer rich completions.
 */
public final class StrikeSyncCommand implements CommandExecutor, TabCompleter {

	public static final String ROOT_PERMISSION = "strikesync.command.use";

	private final StrikeSyncService service;
	private final Map<String, CommandNode> children = new LinkedHashMap<>();

	public StrikeSyncCommand(StrikeSyncService service) {
		this.service = service;
		register(new HelpNode());
		register(new AuthorsNode());
		register(new ReloadNode());
		register(new ToggleNode());
		register(new KnockbackNode());
		register(new CompensationNode());
		register(new PingNode());
	}

	private void register(CommandNode node) {
		children.put(node.name(), node);
	}

	@Override
	public boolean onCommand(@NotNull CommandSender sender, @NotNull Command bukkit,
			@NotNull String label, @NotNull String[] args) {
		if (!sender.hasPermission(ROOT_PERMISSION)) {
			sender.sendMessage(Messages.noPermission());
			return true;
		}
		CommandContext ctx = new CommandContext(sender, args, service);
		if (!ctx.hasMore()) {
			return children.get("help").run(ctx);
		}
		String head = ctx.next().toLowerCase();
		CommandNode child = children.get(head);
		if (child == null) {
			sender.sendMessage(Messages.unknownSubcommand());
			return true;
		}
		return child.run(ctx);
	}

	@Override
	public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
			@NotNull Command bukkit,
			@NotNull String alias,
			@NotNull String[] args) {
		if (!sender.hasPermission(ROOT_PERMISSION))
			return List.of();

		if (args.length <= 1) {
			return filterByPermission(sender, new ArrayList<>(children.keySet()), args.length == 1 ? args[0] : "");
		}

		CommandNode node = children.get(args[0].toLowerCase());
		if (node == null)
			return List.of();

		// Walk the tree following the typed args; the active node is the one we
		// are currently typing into.
		for (int i = 1; i < args.length - 1; i++) {
			CommandNode child = node.child(args[i].toLowerCase());
			if (child == null)
				return List.of();
			node = child;
		}

		String prefix = args[args.length - 1];
		List<String> options = new ArrayList<>(node.complete(
				new CommandContext(sender, Arrays.copyOfRange(args, 1, args.length), service)));
		return startsWith(options, prefix);
	}

	private List<String> filterByPermission(CommandSender sender, List<String> names, String prefix) {
		List<String> out = new ArrayList<>();
		for (String name : names) {
			CommandNode node = children.get(name);
			if (node.permission() == null || sender.hasPermission(node.permission())) {
				out.add(name);
			}
		}
		return startsWith(out, prefix);
	}

	private static List<String> startsWith(List<String> options, String prefix) {
		if (prefix == null || prefix.isEmpty())
			return options;
		String lower = prefix.toLowerCase();
		List<String> out = new ArrayList<>(options.size());
		for (String o : options) {
			if (o.toLowerCase().startsWith(lower))
				out.add(o);
		}
		return out;
	}
}
