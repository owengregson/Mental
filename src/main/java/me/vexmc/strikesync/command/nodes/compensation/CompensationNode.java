package me.vexmc.strikesync.command.nodes.compensation;

import me.vexmc.strikesync.command.CommandNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Container node for {@code /ss compensation}; routes to the
 * enable / disable / status leaves through {@link CommandNode}'s default
 * child-dispatch behavior.
 */
public final class CompensationNode extends CommandNode {

	private final Map<String, CommandNode> children = new LinkedHashMap<>();

	public CompensationNode() {
		super("compensation", "strikesync.command.compensation");
		children.put("enable", new CompensationEnableNode());
		children.put("disable", new CompensationDisableNode());
		children.put("status", new CompensationStatusNode());
	}

	@Override
	public Map<String, CommandNode> children() {
		return children;
	}

	@Override
	public String usage() {
		return "/ss compensation <enable|disable|status>";
	}
}
