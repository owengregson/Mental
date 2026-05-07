package me.vexmc.strikesync.command.nodes.knockback;

import me.vexmc.strikesync.StrikeSyncPlugin;
import me.vexmc.strikesync.command.CommandContext;
import me.vexmc.strikesync.command.CommandNode;
import me.vexmc.strikesync.message.Messages;

public final class KnockbackEnableNode extends CommandNode {

	public KnockbackEnableNode() {
		super("enable", "strikesync.command.knockback");
	}

	@Override
	public String usage() {
		return "/ss knockback enable";
	}

	@Override
	public boolean execute(CommandContext context) {
		context.service().config().set("knockback.enabled", true);
		((StrikeSyncPlugin) context.service().plugin()).reloadAll();
		context.send(Messages.knockbackEnabled());
		return true;
	}
}
