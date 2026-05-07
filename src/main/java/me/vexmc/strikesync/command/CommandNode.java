package me.vexmc.strikesync.command;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A single node in the command tree.
 *
 * <p>
 * Each node owns its name, optional permission, optional aliases, and a set
 * of children. Nodes that do "real work" override {@link #execute}; nodes that
 * exist only to dispatch to children (like {@code /ss knockback}) get the
 * default child-routing behavior for free.
 */
public abstract class CommandNode {

	private final String name;
	private final String permission;

	protected CommandNode(String name, String permission) {
		this.name = name;
		this.permission = permission;
	}

	public final String name() {
		return name;
	}

	/** {@code null} if no permission gate. */
	public final String permission() {
		return permission;
	}

	/** Map child name → child node. Override to provide children. */
	public Map<String, CommandNode> children() {
		return Collections.emptyMap();
	}

	/** Single-line usage shown to the user when arguments are wrong. */
	public abstract String usage();

	/**
	 * Run the leaf or override the default dispatch. Returns {@code true} when
	 * the command was handled (Bukkit treats {@code false} as "show usage from
	 * plugin.yml", which we never want).
	 */
	public boolean execute(CommandContext context) {
		Map<String, CommandNode> kids = children();
		if (kids.isEmpty())
			return false;

		if (!context.hasMore()) {
			context.send(me.vexmc.strikesync.message.Messages.usage(usage()));
			return true;
		}
		String head = context.next().toLowerCase();
		CommandNode child = kids.get(head);
		if (child == null) {
			context.send(me.vexmc.strikesync.message.Messages.usage(usage()));
			return true;
		}
		return child.run(context);
	}

	/** Tab-completion suggestions for the node at the current cursor. */
	public List<String> complete(CommandContext context) {
		return List.copyOf(children().keySet());
	}

	/* ----------------------------- runner ----------------------------- */

	/**
	 * Entry point used by parents (and the root). Handles the permission gate
	 * before delegating to {@link #execute}.
	 */
	public final boolean run(CommandContext context) {
		if (permission != null && !context.sender().hasPermission(permission)) {
			context.send(me.vexmc.strikesync.message.Messages.noPermission());
			return true;
		}
		return execute(context);
	}

	/** Helper: lookup a child for completion routing. */
	public final CommandNode child(String name) {
		return children().get(name);
	}
}

/* package-private utility shared by Commands */
final class CommandUtils {
	private CommandUtils() {
	}

	static List<String> startsWith(List<String> options, String prefix) {
		if (prefix == null || prefix.isEmpty())
			return options;
		String lower = prefix.toLowerCase();
		return options.stream().filter(o -> o.toLowerCase().startsWith(lower)).toList();
	}
}
