package me.vexmc.strikesync.command.nodes;

import me.vexmc.strikesync.command.CommandContext;
import me.vexmc.strikesync.command.CommandNode;
import me.vexmc.strikesync.message.Messages;

public final class AuthorsNode extends CommandNode {

	public AuthorsNode() {
		super("authors", null);
	}

	@Override
	public String usage() {
		return "/ss authors";
	}

	@Override
	public boolean execute(CommandContext context) {
		context.send(Messages.authors());
		return true;
	}
}
