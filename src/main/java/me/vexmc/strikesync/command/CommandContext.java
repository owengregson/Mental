package me.vexmc.strikesync.command;

import me.vexmc.strikesync.core.StrikeSyncService;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;

/**
 * Lightweight command parse state passed down the tree.
 *
 * <p>
 * Holds the sender, the service container, and a shifting view over the
 * argument array. Nodes consume one token at a time via {@link #next()},
 * leaving the rest available to deeper nodes.
 */
public final class CommandContext {

	private final CommandSender sender;
	private final String[] args;
	private final StrikeSyncService service;
	private int cursor;

	public CommandContext(CommandSender sender, String[] args, StrikeSyncService service) {
		this.sender = sender;
		this.args = args;
		this.service = service;
	}

	public CommandSender sender() {
		return sender;
	}

	public StrikeSyncService service() {
		return service;
	}

	public boolean hasMore() {
		return cursor < args.length;
	}

	public int remaining() {
		return Math.max(0, args.length - cursor);
	}

	public String next() {
		return args[cursor++];
	}

	public String peek() {
		return cursor < args.length ? args[cursor] : "";
	}

	public void send(Component message) {
		sender.sendMessage(message);
	}
}
