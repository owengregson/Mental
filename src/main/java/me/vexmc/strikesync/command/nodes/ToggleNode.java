package me.vexmc.strikesync.command.nodes;

import me.vexmc.strikesync.StrikeSyncPlugin;
import me.vexmc.strikesync.command.CommandContext;
import me.vexmc.strikesync.command.CommandNode;
import me.vexmc.strikesync.message.Messages;

public final class ToggleNode extends CommandNode {

	public ToggleNode() {
		super("toggle", "strikesync.command.toggle");
	}

	@Override
	public String usage() {
		return "/ss toggle";
	}

	@Override
	public boolean execute(CommandContext context) {
		boolean current = context.service().config().hitReg().enabled();
		boolean next = !current;
		context.service().config().set("async-hitreg.enabled", next);
		((StrikeSyncPlugin) context.service().plugin()).reloadAll();
		context.send(next ? Messages.hitRegEnabled() : Messages.hitRegDisabled());
		return true;
	}
}
