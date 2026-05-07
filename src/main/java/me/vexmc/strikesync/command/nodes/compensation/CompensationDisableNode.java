package me.vexmc.strikesync.command.nodes.compensation;

import me.vexmc.strikesync.StrikeSyncPlugin;
import me.vexmc.strikesync.command.CommandContext;
import me.vexmc.strikesync.command.CommandNode;
import me.vexmc.strikesync.message.Brand;

public final class CompensationDisableNode extends CommandNode {

	public CompensationDisableNode() {
		super("disable", "strikesync.command.compensation");
	}

	@Override
	public String usage() {
		return "/ss compensation disable";
	}

	@Override
	public boolean execute(CommandContext context) {
		context.service().config().set("compensation.enabled", false);
		((StrikeSyncPlugin) context.service().plugin()).reloadAll();
		context.send(Brand.failure("Latency compensation disabled."));
		return true;
	}
}
