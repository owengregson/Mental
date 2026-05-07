package me.vexmc.strikesync.command.nodes.knockback;

import me.vexmc.strikesync.StrikeSyncPlugin;
import me.vexmc.strikesync.command.CommandContext;
import me.vexmc.strikesync.command.CommandNode;
import me.vexmc.strikesync.message.Messages;

public final class KnockbackDisableNode extends CommandNode {

	public KnockbackDisableNode() {
		super("disable", "strikesync.command.knockback");
	}

	@Override
	public String usage() {
		return "/ss knockback disable";
	}

	@Override
	public boolean execute(CommandContext context) {
		context.service().config().set("knockback.enabled", false);
		((StrikeSyncPlugin) context.service().plugin()).reloadAll();
		context.send(Messages.knockbackDisabled());
		return true;
	}
}
