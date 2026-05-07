package me.vexmc.strikesync.command.nodes;

import me.vexmc.strikesync.command.CommandContext;
import me.vexmc.strikesync.command.CommandNode;
import me.vexmc.strikesync.message.Messages;

public final class HelpNode extends CommandNode {

	public HelpNode() {
		super("help", null);
	}

	@Override
	public String usage() {
		return "/ss help";
	}

	@Override
	public boolean execute(CommandContext context) {
		context.send(Messages.help());
		return true;
	}
}
