package me.vexmc.strikesync.command.nodes.knockback;

import me.vexmc.strikesync.command.CommandNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Container node for {@code /ss knockback}; routes to the enable / disable /
 * status leaves through the default child-dispatch behavior in
 * {@link CommandNode#execute}.
 */
public final class KnockbackNode extends CommandNode {

	private final Map<String, CommandNode> children = new LinkedHashMap<>();

	public KnockbackNode() {
		super("knockback", "strikesync.command.knockback");
		children.put("enable", new KnockbackEnableNode());
		children.put("disable", new KnockbackDisableNode());
		children.put("status", new KnockbackStatusNode());
	}

	@Override
	public Map<String, CommandNode> children() {
		return children;
	}

	@Override
	public String usage() {
		return "/ss knockback <enable|disable|status>";
	}
}
